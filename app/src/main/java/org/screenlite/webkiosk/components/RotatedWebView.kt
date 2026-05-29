package org.screenlite.webkiosk.components

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

class RotatedWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {
    var appliedRotation: Float = 0f
        set(value) {
            if (field == value) return  // skip no-op — prevents spurious requestLayout during load
            field = value
            rotation = value
            requestLayout()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (appliedRotation % 180f == 0f) {
            // Normal orientation — let super run with the real specs so the renderer
            // gets its internal layout pass, then confirm our dimensions.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(parentWidth, parentHeight)
        } else {
            // 90/270° — swap width/height so the rotated content fills the screen.
            val swappedW = MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.EXACTLY)
            val swappedH = MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY)
            super.onMeasure(swappedW, swappedH)
            setMeasuredDimension(parentHeight, parentWidth)
        }
    }
}