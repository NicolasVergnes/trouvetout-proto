package app.trouvetout.android.print

import android.content.Context
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateUtils
import app.trouvetout.android.R
import app.trouvetout.core.data.repository.Inventory
import app.trouvetout.core.data.repository.InventoryLine
import app.trouvetout.core.domain.model.LocationStatus
import java.io.FileOutputStream
import java.io.IOException

/**
 * C16: a place or a container printed as text through the system print service (which also offers « save as PDF »).
 * The pages are drawn on a `PrintedPdfDocument` at the printer's page size; no WebView (`tools/check-network.sh`).
 */
object InventoryPrinter {
    fun print(
        context: Context,
        inventory: Inventory,
    ) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return
        manager.print(inventory.title, InventoryPrintAdapter(context, entries(context, inventory)), null)
    }

    /** The text of the document, one entry per paragraph, in order. */
    fun entries(
        context: Context,
        inventory: Inventory,
    ): List<PrintEntry> = buildList {
        add(PrintEntry(inventory.title, PrintEntry.Style.TITLE))
        if (inventory.path.isNotEmpty()) add(PrintEntry(inventory.path.joinToString(" › "), PrintEntry.Style.NOTE))
        val today = System.currentTimeMillis()
        val dateFlags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR
        val printedOn = DateUtils.formatDateTime(context, today, dateFlags)
        add(PrintEntry(context.getString(R.string.print_printed_on, printedOn), PrintEntry.Style.NOTE))
        if (inventory.lines.isEmpty()) add(PrintEntry(context.getString(R.string.print_empty), PrintEntry.Style.BODY))
        for (line in inventory.lines) add(PrintEntry(text(context, line), styleOf(line.kind), line.depth))
        if (inventory.hiddenCount > 0) {
            val count = inventory.hiddenCount
            val hidden = context.resources.getQuantityString(R.plurals.print_hidden, count, count)
            add(PrintEntry(hidden, PrintEntry.Style.NOTE))
        }
    }

    private fun styleOf(kind: InventoryLine.Kind) = when (kind) {
        InventoryLine.Kind.PLACE -> PrintEntry.Style.PLACE
        InventoryLine.Kind.CONTAINER -> PrintEntry.Style.CONTAINER
        InventoryLine.Kind.ITEM -> PrintEntry.Style.BODY
    }

    private fun text(
        context: Context,
        line: InventoryLine,
    ): String {
        val quantity =
            line.quantity?.let { q ->
                val number = if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()
                line.unit?.let { "$number $it" } ?: "× $number"
            }
        val status =
            when (line.status) {
                LocationStatus.TAKEN -> context.getString(R.string.print_taken)
                LocationStatus.LENT -> context.getString(R.string.print_lent, line.lentTo.orEmpty())
                LocationStatus.STORED -> null
            }
        return listOfNotNull(line.label, quantity, status).joinToString(" · ")
    }
}

/** One paragraph of a printed inventory. */
data class PrintEntry(
    val text: String,
    val style: Style,
    val depth: Int = 0,
) {
    enum class Style { TITLE, NOTE, PLACE, CONTAINER, BODY }
}

/** Lays the entries out on the printer's pages and writes them as PDF. */
class InventoryPrintAdapter(
    private val context: Context,
    private val entries: List<PrintEntry>,
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null
    private var pages: List<List<Placed>> = emptyList()

    /** One laid-out paragraph and its position on its page. */
    class Placed(
        val layout: StaticLayout,
        val x: Float,
        val y: Float,
    )

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val document = PrintedPdfDocument(context, newAttributes)
        val page = document.startPage(0)
        val area = page.info.contentRect
        document.finishPage(page)
        document.close()
        pages = paginate(entries, area.left.toFloat(), area.top.toFloat(), area.width(), area.height())
        val info =
            PrintDocumentInfo
                .Builder("inventory.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        ranges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val document = PrintedPdfDocument(context, attributes ?: return callback.onWriteFailed(null))
        try {
            write(document, ranges, cancellationSignal)
            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
                return
            }
            FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.javaClass.simpleName)
        } finally {
            document.close()
        }
    }

    private fun write(
        document: PrintedPdfDocument,
        ranges: Array<out PageRange>,
        cancellationSignal: CancellationSignal?,
    ) {
        for ((index, placed) in pages.withIndex()) {
            if (cancellationSignal?.isCanceled == true) return
            if (ranges.none { it == PageRange.ALL_PAGES || index in it.start..it.end }) continue
            val page = document.startPage(index)
            draw(page.canvas, placed)
            document.finishPage(page)
        }
    }

    companion object {
        /** A4 in points, with half-inch margins: the size of [writePdf]. */
        private const val A4_WIDTH = 595
        private const val A4_HEIGHT = 842
        private const val MARGIN = 36

        /** The same pages written as an A4 PDF, without the print service; returns the number of pages. */
        fun writePdf(
            entries: List<PrintEntry>,
            out: java.io.OutputStream,
        ): Int {
            val margin = MARGIN.toFloat()
            val pages = paginate(entries, margin, margin, A4_WIDTH - 2 * MARGIN, A4_HEIGHT - 2 * MARGIN)
            val document = PdfDocument()
            try {
                for ((index, placed) in pages.withIndex()) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, index + 1).create())
                    draw(page.canvas, placed)
                    document.finishPage(page)
                }
                document.writeTo(out)
            } finally {
                document.close()
            }
            return pages.size
        }

        private fun draw(
            canvas: android.graphics.Canvas,
            placed: List<Placed>,
        ) {
            for (item in placed) {
                canvas.save()
                canvas.translate(item.x, item.y)
                item.layout.draw(canvas)
                canvas.restore()
            }
        }

        private const val INDENT = 18f
        private const val GAP = 4f

        /** Pure layout: paragraphs of [entries] cut into pages of [height] points. */
        fun paginate(
            entries: List<PrintEntry>,
            left: Float,
            top: Float,
            width: Int,
            height: Int,
        ): List<List<Placed>> {
            val pages = ArrayList<List<Placed>>()
            var current = ArrayList<Placed>()
            var y = 0f
            for (entry in entries) {
                val indent = INDENT * entry.depth
                val layout = layoutOf(entry, (width - indent).toInt().coerceAtLeast(MIN_WIDTH))
                if (y > 0f && y + layout.height > height) {
                    pages += current
                    current = ArrayList()
                    y = 0f
                }
                current += Placed(layout, left + indent, top + y)
                y += layout.height + GAP
            }
            pages += current
            return pages
        }

        private const val MIN_WIDTH = 72
        private const val TITLE_POINTS = 18f
        private const val NOTE_POINTS = 9f
        private const val BODY_POINTS = 11f

        private fun layoutOf(
            entry: PrintEntry,
            width: Int,
        ): StaticLayout {
            val paint =
                TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                    textSize =
                        when (entry.style) {
                            PrintEntry.Style.TITLE -> TITLE_POINTS
                            PrintEntry.Style.NOTE -> NOTE_POINTS
                            else -> BODY_POINTS
                        }
                    typeface =
                        when (entry.style) {
                            PrintEntry.Style.TITLE, PrintEntry.Style.PLACE -> Typeface.DEFAULT_BOLD
                            PrintEntry.Style.CONTAINER -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                            else -> Typeface.DEFAULT
                        }
                }
            return StaticLayout.Builder
                .obtain(entry.text, 0, entry.text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }
    }
}
