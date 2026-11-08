package com.drake.net.sample.modern

import android.app.Application
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.drake.net.result.NetFailure
import com.drake.net.result.NetState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class NetStateDisplayTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun loadingAdapterShowsOnlyWhileLoading() {
        val view = ProgressBar(context)

        bindNetLoading(view, NetState.Loading)
        assertEquals(View.VISIBLE, view.visibility)

        bindNetLoading(view, NetState.Success("value"))
        assertEquals(View.GONE, view.visibility)

        bindNetLoading(view, null)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun textAdapterRendersEveryStateWithoutRawExceptionMessage() {
        val view = TextView(context)

        bindNetStateText(view, null)
        assertEquals("等待请求", view.text.toString())

        bindNetStateText(view, NetState.Success("value"))
        assertEquals("成功：value", view.text.toString())

        bindNetStateText(view, NetState.Failure(NetFailure(IOException("secret detail"))))
        assertEquals("失败：NETWORK", view.text.toString())
    }
}
