package ca.canadavhs.snapmobile.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

object CommandQrDecoder {
    fun decode(width: Int,height: Int,pixels: IntArray): List<CaptureCommand> {
        require(width>0 && height>0 && pixels.size==width*height)
        val source=RGBLuminanceSource(width,height,pixels)
        val commands=linkedSetOf<CaptureCommand>()
        for(inverted in listOf(false,true)) {
            val bitmap=BinaryBitmap(HybridBinarizer(if(inverted) source.invert() else source))
            val results=try {QRCodeMultiReader().decodeMultiple(bitmap,mapOf(DecodeHintType.TRY_HARDER to true)).toList()} catch(_:NotFoundException) {emptyList()}
            results.mapNotNull {parseCaptureCommand(it.text)}.forEach {commands.add(it)}
        }
        return commands.toList()
    }
}
