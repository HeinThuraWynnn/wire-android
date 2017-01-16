/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.{GradientDrawable, StateListDrawable}
import android.util.AttributeSet
import com.waz.zclient.ui.R
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.ui.theme.ThemeUtils
import com.waz.zclient.ui.utils.ColorUtils

object GlyphButton {
  private val PRESSED_ALPHA__LIGHT: Float = 0.32f
  private val PRESSED_ALPHA__DARK: Float = 0.40f
  private val TRESHOLD: Float = 0.55f
  private val DARKEN_FACTOR: Float = 0.1f
}

class GlyphButton(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends GlyphTextView(context, attrs, defStyleAttr) {
  private var alphaPressed: Float = 0

  def this(context: Context, attrs: AttributeSet) {
    this(context, attrs, 0)
  }

  def this(context: Context) {
    this(context, null)
  }

  def setPressedBackgroundColor(color: Int) {
    setBackgroundColor(Color.TRANSPARENT, color)
  }

  def setSolidBackgroundColor(color: Int) {
    setBackgroundColor(color, color)
  }

  private def setBackgroundColor(defaultColor: Int, pColor: Int) {
    var pressedColor = pColor
    if (ThemeUtils.isDarkTheme(getContext)) {
      alphaPressed = GlyphButton.PRESSED_ALPHA__DARK
    }
    else {
      alphaPressed = GlyphButton.PRESSED_ALPHA__LIGHT
    }
    val avg: Float = (Color.red(pressedColor) + Color.blue(pressedColor) + Color.green(pressedColor)) / (3 * 255.0f)
    if (avg > GlyphButton.TRESHOLD) {
      val darken: Float = 1.0f - GlyphButton.DARKEN_FACTOR
      pressedColor = Color.rgb((Color.red(pressedColor) * darken).toInt, (Color.green(pressedColor) * darken).toInt, (Color.blue(pressedColor) * darken).toInt)
    }
    val pressed: Int = ColorUtils.injectAlpha(alphaPressed, pressedColor)
    val pressedBgColor: GradientDrawable = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, Array[Int](pressed, pressed))
    pressedBgColor.setShape(GradientDrawable.OVAL)
    val defaultBgColor: GradientDrawable = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, Array[Int](defaultColor, defaultColor))
    defaultBgColor.setShape(GradientDrawable.OVAL)
    val states: StateListDrawable = new StateListDrawable
    states.addState(Array[Int](android.R.attr.state_pressed), pressedBgColor)
    states.addState(Array[Int](android.R.attr.state_focused), pressedBgColor)
    states.addState(Array[Int](-android.R.attr.state_enabled), pressedBgColor)
    states.addState(new Array[Int](0), defaultBgColor)
    setBackground(states)
    invalidate()
  }

  def initTextColor(selectedColor: Int) {
    var pressedColor: Int = getResources.getColor(R.color.text__primary_dark_40)
    var focusedColor: Int = pressedColor
    var enabledColor: Int = getResources.getColor(R.color.text__primary_dark)
    var disabledColor: Int = getResources.getColor(R.color.text__primary_dark_16)
    if (!ThemeUtils.isDarkTheme(getContext)) {
      pressedColor = getResources.getColor(R.color.text__primary_light__40)
      focusedColor = pressedColor
      enabledColor = getResources.getColor(R.color.text__primary_light)
      disabledColor = getResources.getColor(R.color.text__primary_light_16)
    }
    val colors: Array[Int] = Array(pressedColor, focusedColor, selectedColor, enabledColor, disabledColor)
    val states: Array[Array[Int]] = Array(Array(android.R.attr.state_pressed), Array(android.R.attr.state_focused), Array(android.R.attr.state_selected), Array(android.R.attr.state_enabled), Array(-android.R.attr.state_enabled))
    val colorStateList: ColorStateList = new ColorStateList(states, colors)
    super.setTextColor(colorStateList)
  }
}
