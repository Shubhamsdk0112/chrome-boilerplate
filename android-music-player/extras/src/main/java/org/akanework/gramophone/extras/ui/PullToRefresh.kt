/*
 *     Copyright (C) 2026 Gramophone extras contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.extras.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors

/**
 * Pull-to-refresh on the library screen.
 *
 * A SwipeRefreshLayout around a ViewPager2 has no idea whether the *current
 * page's* list is scrolled to the top, so it would fire on any downward drag.
 * This looks up the page's RecyclerView and also refuses while the collapsing
 * app bar is not fully expanded, so a pull first expands the header, then
 * refreshes — the same feel as the stock Gmail/Files apps.
 */
object PullToRefresh {

    @JvmStatic
    fun attach(
        swipe: SwipeRefreshLayout,
        pager: ViewPager2,
        appBar: AppBarLayout,
        onRefresh: () -> Unit,
    ) {
        val context = swipe.context
        // Material's attrs are not visible from a library module's R; look
        // them up by name in the merged app namespace instead.
        swipe.setColorSchemeColors(attrColor(swipe, "colorPrimary", android.R.attr.colorPrimary))
        swipe.setProgressBackgroundColorSchemeColor(
            attrColor(swipe, "colorSurfaceContainerHigh", android.R.attr.colorBackground),
        )
        swipe.setProgressViewOffset(false, 0, (64 * context.resources.displayMetrics.density).toInt())

        var appBarOffset = 0
        appBar.addOnOffsetChangedListener { _, verticalOffset -> appBarOffset = verticalOffset }

        swipe.setOnChildScrollUpCallback { _, _ ->
            if (appBarOffset != 0) return@setOnChildScrollUpCallback true
            when (val scrollable = currentScrollable(pager)) {
                is VerticalScrollReporter -> scrollable.canScrollUp()
                null -> false
                else -> scrollable.canScrollVertically(-1)
            }
        }
        swipe.setOnRefreshListener(onRefresh)
    }

    private fun attrColor(view: View, name: String, fallback: Int): Int {
        val id = view.resources.getIdentifier(name, "attr", view.context.packageName)
        return MaterialColors.getColor(view, if (id != 0) id else fallback)
    }

    /**
     * The scrolling thing inside the page that is currently shown: a library
     * tab's RecyclerView, or a Compose page that reports for itself.
     */
    private fun currentScrollable(pager: ViewPager2): View? {
        val inner = pager.getChildAt(0) as? RecyclerView ?: return null
        val page = inner.layoutManager?.findViewByPosition(pager.currentItem) ?: return null
        return findScrollable(page)
    }

    private fun findScrollable(view: View): View? {
        if (view is RecyclerView || view is VerticalScrollReporter) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            findScrollable(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

/**
 * A page that is not a RecyclerView (the Compose Home tab) says here whether
 * its content can scroll up, so pull-to-refresh only takes a drag at the top.
 */
interface VerticalScrollReporter {
    fun canScrollUp(): Boolean
}
