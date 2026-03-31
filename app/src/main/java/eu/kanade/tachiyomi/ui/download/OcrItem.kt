package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R

internal class OcrItem(
    val ocrQueueItem: OcrQueueChapterItem,
) : AbstractFlexibleItem<OcrHolder>() {

    override fun getLayoutRes(): Int {
        return R.layout.download_item
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): OcrHolder {
        return OcrHolder(view, adapter as OcrAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: OcrHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(ocrQueueItem)
    }

    override fun isDraggable(): Boolean {
        return ocrQueueItem.state != OcrQueueItemState.Scanning
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is OcrItem) {
            return ocrQueueItem.chapterId == other.ocrQueueItem.chapterId
        }
        return false
    }

    override fun hashCode(): Int {
        return ocrQueueItem.chapterId.hashCode()
    }
}
