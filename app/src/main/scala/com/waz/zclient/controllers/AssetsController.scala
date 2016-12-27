package com.waz.zclient.controllers

import android.app.DownloadManager
import android.content.{Context, Intent}
import android.net.Uri
import android.support.v7.app.AppCompatDialog
import android.text.TextUtils
import android.util.TypedValue
import android.view.{Gravity, View}
import android.widget.{TextView, Toast}
import com.waz.api.{ImageAsset, Message}
import com.waz.model.{AssetData, AssetId, MessageData, Mime}
import com.waz.service.ZMessaging
import com.waz.service.assets.GlobalRecordAndPlayService
import com.waz.service.assets.GlobalRecordAndPlayService.{AssetMediaKey, Content, UnauthenticatedContent}
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.controllers.AssetsController.PlaybackControls
import com.waz.zclient.controllers.singleimage.ISingleImageController
import com.waz.zclient.core.api.scala.ModelObserver
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.pages.main.conversation.views.MessageBottomSheetDialog.MessageAction
import com.waz.zclient.ui.utils.TypefaceUtils
import com.waz.zclient.utils.AssetUtils
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.Duration

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._

import scala.PartialFunction._
import scala.concurrent.Promise
import scala.util.Success

class AssetsController(implicit context: Context, inj: Injector, ec: EventContext) extends Injectable { controller =>
  import Threading.Implicits.Ui

  val zms = inject[Signal[ZMessaging]]
  val assets = zms.map(_.assets)

  val messages = zms.map(_.messages)

  lazy val messageActionsController = inject[MessageActionsController]
  lazy val singleImage = inject[ISingleImageController]


  messageActionsController.onMessageAction {
    case (MessageAction.OPEN_FILE, msg) =>
      zms.head.flatMap(_.assetsStorage.get(AssetId(msg.getId))) foreach {
        case Some(asset) => openFile(asset, showDialog = false)
        case None => // TODO: show error
      }
    case _ => // ignore
  }

  def assetSignal(mes: Signal[MessageData]) = mes.flatMap(m => assets.flatMap(_.assetSignal(m.assetId)))

  def downloadProgress(id: AssetId) = assets.flatMap(_.downloadProgress(id))

  def uploadProgress(id: AssetId) = assets.flatMap(_.uploadProgress(id))

  def cancelUpload(m: MessageData) = assets.currentValue.foreach(_.cancelUpload(m.assetId, m.id))

  def cancelDownload(m: MessageData) = assets.currentValue.foreach(_.cancelDownload(m.assetId))

  def retry(m: MessageData) = if (m.state == Message.Status.FAILED || m.state == Message.Status.FAILED_READ) messages.currentValue.foreach(_.retryMessageSending(m.convId, m.id))

  def getPlaybackControls(asset: Signal[AssetData]): Signal[PlaybackControls] = asset.flatMap { a =>
    if (cond(a.mime.orDefault) { case Mime.Audio() => true }) Signal.const(new PlaybackControls(a.id, controller))
    else Signal.empty[PlaybackControls]
  }

  // display full screen image for given message
  def showSingleImage(msg: MessageData, container: View) = {
    // FIXME: don't use java api, it's ugly and buggy
    // obtain java message and wait for image asset to be loaded,
    // this is required for SingleImageFragment to work properly
    val m = ZMessaging.currentUi.messages.cachedOrNew(msg.id)
    val p = Promise[Message]()
    val imObserver = new ModelObserver[ImageAsset] {
      override def updated(model: ImageAsset): Unit = if (model.getWidth > 0) p.trySuccess(m)
    }
    val observer = new ModelObserver[Message] {
      override def updated(model: Message): Unit = imObserver.setAndUpdate(model.getImage)
    }
    observer.setAndUpdate(m)
    p.future.onComplete { _ =>
      observer.clear()
      imObserver.clear()
    }
    p.future foreach { m =>
      verbose(s"message loaded, opening single image for $m")
      singleImage.setViewReferences(container)
      singleImage.showSingleImage(m)
    }
  }

  def openFile(asset: AssetData, showDialog: Boolean = true) =  // TODO: do we need showDialog param? shouldn't we decide that by mime type?
    assets.head.flatMap(_.getContentUri(asset.id)) foreach {
      case Some(uri) if showDialog =>
        showOpenFileDialog(uri, asset)
      case Some(uri) =>
        context.startActivity(AssetUtils.getOpenFileIntent(uri, asset.mime.orDefault.str))
      case None =>
      // TODO: display error
    }

  def showOpenFileDialog(uri: Uri, a: AssetData) = {
    val intent = AssetUtils.getOpenFileIntent(uri, a.mime.orDefault.str)
    val fileCanBeOpened = AssetUtils.fileTypeCanBeOpened(context.getPackageManager, intent)

    //TODO tidy up
    //TODO there is also a weird flash or double-dialog issue when you click outside of the dialog
    val dialog = new AppCompatDialog(context)
    a.name.foreach(dialog.setTitle)
    dialog.setContentView(R.layout.file_action_sheet_dialog)

    val title = dialog.findViewById(R.id.title).asInstanceOf[TextView]
    title.setEllipsize(TextUtils.TruncateAt.MIDDLE)
    title.setTypeface(TypefaceUtils.getTypeface(getString(R.string.wire__typeface__medium)))
    title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getDimenPx(R.dimen.wire__text_size__regular))
    title.setGravity(Gravity.CENTER)

    val openButton = dialog.findViewById(R.id.ttv__file_action_dialog__open).asInstanceOf[TextView]
    val noAppFoundLabel = dialog.findViewById(R.id.ttv__file_action_dialog__open__no_app_found)
    val saveButton = dialog.findViewById(R.id.ttv__file_action_dialog__save)

    if (fileCanBeOpened) {
      noAppFoundLabel.setVisibility(View.GONE)
      openButton.setAlpha(1f)
      openButton.setOnClickListener(new View.OnClickListener() {
        def onClick(v: View) = {
          context.startActivity(intent)
          dialog.dismiss()
        }
      })
    }
    else {
      noAppFoundLabel.setVisibility(View.VISIBLE)
      val disabledAlpha = getResourceFloat(R.dimen.button__disabled_state__alpha)
      openButton.setAlpha(disabledAlpha)
    }

    saveButton.setOnClickListener(new View.OnClickListener() {
      def onClick(v: View) = {
        dialog.dismiss()
        saveToDownloads(a)
      }
    })

    dialog.show()
  }

  def saveToDownloads(asset: AssetData) =
    assets.head.flatMap(_.saveAssetToDownloads(asset)).onComplete {
      case Success(Some(file)) =>
        val uri = Uri.fromFile(file)
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE).asInstanceOf[DownloadManager]
        downloadManager.addCompletedDownload(asset.name.get, asset.name.get, false, asset.mime.orDefault.str, uri.getPath, asset.sizeInBytes, true)
        Toast.makeText(context, com.waz.zclient.ui.R.string.content__file__action__save_completed, Toast.LENGTH_SHORT).show()

        val intent: Intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        intent.setData(uri)
        context.sendBroadcast(intent)
      case _ =>
    }(Threading.Ui)
}

object AssetsController {

  class PlaybackControls(assetId: AssetId, controller: AssetsController) {
    val rAndP = controller.zms.map(_.global.recordingAndPlayback)

    val isPlaying = rAndP.flatMap(rP => rP.isPlaying(AssetMediaKey(assetId)))
    val playHead = rAndP.flatMap(rP => rP.playhead(AssetMediaKey(assetId)))

    private def rPAction(f: (GlobalRecordAndPlayService, AssetMediaKey, Content, Boolean) => Unit): Unit = {
      for {
        as <- controller.assets.currentValue
        rP <- rAndP.currentValue
        isPlaying <- isPlaying.currentValue
      } {
        as.getContentUri(assetId).foreach {
          case Some(uri) => f(rP, AssetMediaKey(assetId), UnauthenticatedContent(uri), isPlaying)
          case None =>
        }(Threading.Background)
      }
    }

    def playOrPause() = rPAction { case (rP, key, content, playing) => if (playing) rP.pause(key) else rP.play(key, content) }

    def setPlayHead(duration: Duration) = rPAction { case (rP, key, content, playing) => rP.setPlayhead(key, content, duration) }
  }
}