/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.ext

import android.view.View

/**
 * Returns false if the view or any of its ancestors are not visible
 */
val View.isEffectivelyVisible: Boolean get() {
    var node: View? = this
    while (node != null) {
        if (node.visibility != View.VISIBLE) return false
        node = node.parent as? View
    }
    return true
}

// TODO test this.  likely off by 1 error here
fun View.itAndAncestorsAreVisible(generationsUp: Int = Int.MAX_VALUE): Boolean {
    var generations = generationsUp
    var node: View? = this
    while (node != null && generations-- > 0) {
        if (node.visibility != View.VISIBLE) return false
        node = node.parent as? View
    }
    return true
}
