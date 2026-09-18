import androidx.media3.ui.PlayerView
import android.content.Context
fun test(context: Context) {
    val pv = PlayerView(context)
    pv.setSurfaceType(PlayerView.SURFACE_TYPE_TEXTURE_VIEW) // Wait, is there a setter?
}
