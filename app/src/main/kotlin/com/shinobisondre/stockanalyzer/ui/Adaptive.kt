package com.shinobisondre.stockanalyzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A list that becomes a two-column (or wider) grid when there's room: one column on the
 * cover screen and in the narrow list pane, two or more when a tab has the whole inner screen.
 * Use [full] for things that should always span the width (headers, search, explanations)
 * and [cell] / items() for cards that can sit side by side.
 */
@Composable
fun AdaptiveGrid(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyStaggeredGridScope.() -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(330.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        content = content,
    )
}

// Item content is wrapped in a Column so several children stack instead of drawing on top of each other.
fun LazyStaggeredGridScope.full(key: Any? = null, content: @Composable LazyStaggeredGridItemScope.() -> Unit) =
    item(key = key, span = StaggeredGridItemSpan.FullLine) { val scope = this; Column(Modifier.fillMaxWidth()) { scope.content() } }

fun LazyStaggeredGridScope.cell(key: Any? = null, content: @Composable LazyStaggeredGridItemScope.() -> Unit) =
    item(key = key) { val scope = this; Column(Modifier.fillMaxWidth()) { scope.content() } }
