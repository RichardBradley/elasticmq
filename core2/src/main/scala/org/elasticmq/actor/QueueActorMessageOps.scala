package org.elasticmq.actor

import scala.annotation.tailrec
import org.elasticmq._
import org.elasticmq.msg._
import org.elasticmq.data.MessageDoesNotExist
import org.elasticmq.data.NewMessageData
import org.elasticmq.data.MessageData
import org.joda.time.DateTime

trait QueueActorMessageOps {
  this: QueueActorStorage =>

  def receiveAndReplyMessageMsg[T](msg: QueueMessageMsg[T]): T = msg match {
    case SendMessage(message) => sendMessage(message)
    case UpdateNextDelivery(messageId, newNextDelivery) => updateNextDelivery(messageId, newNextDelivery)
    case ReceiveMessage(deliveryTime, newNextDelivery) => receiveMessage(deliveryTime, newNextDelivery)
    case DeleteMessage(messageId) => {
      // Just removing the msg from the map. The msg will be removed from the queue when trying to receive it.
      messagesById.remove(messageId.id)
      ()
    }
    case LookupMessage(messageId) => messagesById.get(messageId.id).map(_.toMessageData)
  }

  private def sendMessage(message: NewMessageData) {
    val internalMessage = InternalMessage.from(message)
    messageQueue += internalMessage
    messagesById(internalMessage.id) = internalMessage
  }

  private def updateNextDelivery(messageId: MessageId, newNextDelivery: MillisNextDelivery) = {
    messagesById.get(messageId.id) match {
      case Some(internalMessage) => {
        // Updating
        val oldNextDelivery = internalMessage.nextDelivery
        internalMessage.nextDelivery = newNextDelivery.millis

        if (newNextDelivery.millis < oldNextDelivery) {
          // We have to re-insert the msg, as another msg with a bigger next delivery may be now before it,
          // so the msg wouldn't be correctly received.
          // (!) This may be slow (!)
          messageQueue = messageQueue.filterNot(_.id == internalMessage.id)
          messageQueue += internalMessage
        }
        // Else:
        // Just increasing the next delivery. Common case. It is enough to increase the value in the object. No need to
        // re-insert the msg into the queue, as it will be reinserted if needed during receiving.

        Right(())
      }

      case None => Left(new MessageDoesNotExist(queueData.name, messageId))
    }
  }

  @tailrec
  private def receiveMessage(deliveryTime: Long, newNextDelivery: MillisNextDelivery): Option[MessageData] = {
    if (messageQueue.size == 0) {
      None
    } else {
      val internalMessage = messageQueue.dequeue()
      val id = MessageId(internalMessage.id)
      if (internalMessage.nextDelivery > deliveryTime) {
        // Putting the msg back. That's the youngest msg, so there is no msg that can be received.
        messageQueue += internalMessage
        None
      } else if (messagesById.contains(id.id)) {
        // Putting the msg again into the queue, with a new next delivery
        internalMessage.deliveryReceipt = Some(DeliveryReceipt.generate(id).receipt)
        internalMessage.nextDelivery = newNextDelivery.millis

        internalMessage.receiveCount += 1
        internalMessage.firstReceive = OnDateTimeReceived(new DateTime(deliveryTime))

        messageQueue += internalMessage

        Some(internalMessage.toMessageData)
      } else {
        // Deleted msg - trying again
        receiveMessage(deliveryTime, newNextDelivery)
      }
    }
  }
}
