/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.views

import java.util

import android.content.Context
import android.support.v4.content.ContextCompat
import android.util.AttributeSet
import android.widget.LinearLayout
import com.waz.api.Message
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.zclient.conversation.CollectionController
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.pages.main.conversation.views.MessageBottomSheetDialog
import com.waz.zclient.pages.main.conversation.views.MessageBottomSheetDialog.{Callback, MessageAction}
import com.waz.zclient.utils.RichView
import com.waz.zclient.{R, ViewHelper}

class SingleImageViewToolbar(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  inflate(R.layout.single_image_view_toolbar_layout)

  private lazy val zms = inject[Signal[ZMessaging]]
  private lazy val messageActionsController = inject[MessageActionsController]
  private lazy val collectionController = inject[CollectionController]

  private val likeButton: GlyphButton = findById(R.id.toolbar_like)
  private val downloadButton: GlyphButton = findById(R.id.toolbar_download)
  private val shareButton: GlyphButton = findById(R.id.toolbar_share)
  private val deleteButton: GlyphButton = findById(R.id.toolbar_delete)
  private val viewButton: GlyphButton = findById(R.id.toolbar_view)

  private val updateSig: SourceSignal[Boolean] = Signal[Boolean](false)

  private val messageSig = Signal(zms, collectionController.focusedItem, updateSig).flatMap {
    case (z, Some(msg), _) => Signal.future(z.msgAndLikes.combineWithLikes(msg))
    case _ => Signal.empty[MessageAndLikes]
  }

  messageSig.on(Threading.Ui){ updateLikeButton }

  zms.on(Threading.Ui){ z =>
    z.msgAndLikes.onUpdate{
      _ => updateSig.mutate(!_)
    }
  }

  messageActionsController.onDeleteConfirmed.on(Threading.Ui){
    _ => collectionController.focusedItem ! None
  }

  private def message: Option[Message] = messageSig.currentValue.map(ZMessaging.currentUi.messages.cachedOrUpdated(_))

  private def updateLikeButton(messageAndLikes: MessageAndLikes): Unit = {
    if (messageAndLikes.likedBySelf) {
      likeButton.setText(R.string.glyph__liked)
    } else {
      likeButton.setText(R.string.glyph__like)
    }
  }

  private val messageActionCallback = new Callback {
    override def onAction(action: MessageAction, message: Message): Unit = {
      messageActionsController.onMessageAction ! (action, message)
    }
  }

  likeButton.setPressedBackgroundColor(ContextCompat.getColor(getContext, R.color.light_graphite))
  downloadButton.setPressedBackgroundColor(ContextCompat.getColor(getContext, R.color.light_graphite))
  shareButton.setPressedBackgroundColor(ContextCompat.getColor(getContext, R.color.light_graphite))
  deleteButton.setPressedBackgroundColor(ContextCompat.getColor(getContext, R.color.light_graphite))
  viewButton.setPressedBackgroundColor(ContextCompat.getColor(getContext, R.color.light_graphite))

  likeButton.onClick( message.foreach(msg => messageActionCallback.onAction(MessageAction.LIKE, msg)))
  downloadButton.onClick( message.foreach(msg => messageActionCallback.onAction(MessageAction.SAVE, msg)))

  shareButton.onClick( message.foreach(msg => messageActionCallback.onAction(MessageAction.FORWARD, msg)))

  deleteButton.onClick( message.foreach{msg =>
    if (msg.getUser.isMe) {
      val options = new util.HashSet[MessageAction]()
      options.add(MessageAction.DELETE_LOCAL)
      options.add(MessageAction.DELETE_GLOBAL)
      val dialog = new MessageBottomSheetDialog(context, R.style.message__bottom_sheet__base, msg, true, messageActionCallback, options)
      dialog.show()
    } else {
      messageActionCallback.onAction(MessageAction.DELETE_LOCAL, msg)
    }
  })

}
