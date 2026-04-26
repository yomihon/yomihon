package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem

/**
 * Adapter storing a list of ocr scans.
 *
 * @param ocrItemListener Listener called when an item of the list is released.
 */
internal class OcrAdapter(val ocrItemListener: OcrItemListener) : FlexibleAdapter<AbstractFlexibleItem<*>>(
    null,
    ocrItemListener,
    true,
) {

    interface OcrItemListener {
        fun onItemReleased(position: Int)
        fun onMenuItemClick(position: Int, menuItem: MenuItem)
    }
}
