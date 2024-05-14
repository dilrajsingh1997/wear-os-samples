/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.wearable.alpha

import android.animation.ValueAnimator
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.SurfaceHolder
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.data.ComplicationExperimental
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.example.android.wearable.alpha.data.watchface.ColorStyleIdAndResourceIds
import com.example.android.wearable.alpha.data.watchface.HeartRateRepository
import com.example.android.wearable.alpha.data.watchface.WatchFaceColorPalette.Companion.convertToWatchFaceColorPalette
import com.example.android.wearable.alpha.data.watchface.WatchFaceData
import com.example.android.wearable.alpha.utils.COLOR_STYLE_SETTING
import com.example.android.wearable.alpha.utils.DRAW_HOUR_PIPS_STYLE_SETTING
import com.example.android.wearable.alpha.utils.Logger
import com.example.android.wearable.alpha.utils.WATCH_HAND_LENGTH_STYLE_SETTING
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


// Default for how long each frame is displayed at expected frame rate.
private const val FRAME_PERIOD_MS_DEFAULT: Long = 16L

/**
 * Renders watch face via data in Room database. Also, updates watch face state based on setting
 * changes by user via [userStyleRepository.addUserStyleListener()].
 */
class AnalogWatchCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<AnalogWatchCanvasRenderer.AnalogSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    FRAME_PERIOD_MS_DEFAULT,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
        }
    }

    val x = ValueAnimator()

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val complicationDrawable by lazy {
        ComplicationDrawable(context).apply {
            activeStyle.run {
                backgroundColor = Color.CYAN
                textColor = Color.RED
                titleColor = Color.GREEN
                textSize = 100
            }
        }
    }

    private val heartRateRepository = HeartRateRepository(context)

    // Represents all data needed to render the watch face. All value defaults are constants. Only
    // three values are changeable by the user (color scheme, ticks being rendered, and length of
    // the minute arm). Those dynamic values are saved in the watch face APIs and we update those
    // here (in the renderer) through a Kotlin Flow.
    private var watchFaceData: WatchFaceData = WatchFaceData()

    // Converts resource ids into Colors and ComplicationDrawable.
    private var watchFaceColors = convertToWatchFaceColorPalette(
        context,
        watchFaceData.activeColorStyle,
        watchFaceData.ambientColorStyle
    )

    private var shouldFetchBattery = true
    private var batteryLevel = 0f

    // Initializes paint object for painting the clock hands with default values.
    private val clockHandPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth =
            context.resources.getDimensionPixelSize(R.dimen.clock_hand_stroke_width).toFloat()
    }

//    private val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { intentFilter ->
//        context.registerReceiver(null, intentFilter)
//    }

    private val heartRateTopMargin by lazy {
        context.resources.getDimensionPixelSize(R.dimen.heart_rate_top_margin).toFloat()
    }

    private val batteryRadius by lazy {
        context.resources.getDimensionPixelSize(R.dimen.battery_radius).toFloat()
    }

    private val dateRightMargin by lazy {
        context.resources.getDimensionPixelSize(R.dimen.date_right_margin).toFloat()
    }

    private val customDataElements = Paint()
        .apply {
            isAntiAlias = true
            textSize = context.resources.getDimensionPixelSize(R.dimen.hour_mark_size).toFloat()
            color = Color.WHITE
            style = Paint.Style.FILL
        }

    private val outerElementPaint = Paint().apply {
        isAntiAlias = true
    }

    // Used to paint the main hour hand text with the hour pips, i.e., 3, 6, 9, and 12 o'clock.
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = context.resources.getDimensionPixelSize(R.dimen.hour_mark_size).toFloat()
    }

    private lateinit var hourHandFill: Path
    private lateinit var hourHandBorder: Path
    private lateinit var minuteHandFill: Path
    private lateinit var minuteHandBorder: Path
    private lateinit var secondHand: Path

    // Changed when setting changes cause a change in the minute hand arm (triggered by user in
    // updateUserStyle() via userStyleRepository.addUserStyleListener()).
    private var armLengthChangedRecalculateClockHands: Boolean = false

    // Default size of watch face drawing area, that is, a no size rectangle. Will be replaced with
    // valid dimensions from the system.
    private var currentWatchFaceSize = Rect(0, 0, 0, 0)

    init {
        scope.launch {
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateWatchFaceData(userStyle)
            }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    /*
     * Triggered when the user makes changes to the watch face through the settings activity. The
     * function is called by a flow.
     */
    private fun updateWatchFaceData(userStyle: UserStyle) {
        Logger.d(TAG, "updateWatchFace(): $userStyle")

        var newWatchFaceData: WatchFaceData = watchFaceData

        // Loops through user style and applies new values to watchFaceData.
        for (options in userStyle) {
            when (options.key.id.toString()) {
                COLOR_STYLE_SETTING -> {
                    val listOption = options.value as
                        UserStyleSetting.ListUserStyleSetting.ListOption

                    newWatchFaceData = newWatchFaceData.copy(
                        activeColorStyle = ColorStyleIdAndResourceIds.getColorStyleConfig(
                            listOption.id.toString()
                        ),
                        ambientColorStyle = ColorStyleIdAndResourceIds.getColorStyleConfig(
                            listOption.id.toString()
                        )
                    )
                }

                DRAW_HOUR_PIPS_STYLE_SETTING -> {
                    val booleanValue = options.value as
                        UserStyleSetting.BooleanUserStyleSetting.BooleanOption

                    newWatchFaceData = newWatchFaceData.copy(
                        drawHourPips = booleanValue.value
                    )
                }

                WATCH_HAND_LENGTH_STYLE_SETTING -> {
                    val doubleValue = options.value as
                        UserStyleSetting.DoubleRangeUserStyleSetting.DoubleRangeOption

                    // The arm lengths are usually only calculated the first time the watch face is
                    // loaded to reduce the ops in the onDraw(). Because we updated the minute hand
                    // watch length, we need to trigger a recalculation.
                    armLengthChangedRecalculateClockHands = true

                    // Updates length of minute hand based on edits from user.
                    val newMinuteHandDimensions = newWatchFaceData.minuteHandDimensions.copy(
                        lengthFraction = doubleValue.value.toFloat()
                    )

                    newWatchFaceData = newWatchFaceData.copy(
                        minuteHandDimensions = newMinuteHandDimensions
                    )
                }
            }
        }

        // Only updates if something changed.
        if (watchFaceData != newWatchFaceData) {
            watchFaceData = newWatchFaceData

            // Recreates Color and ComplicationDrawable from resource ids.
            watchFaceColors = convertToWatchFaceColorPalette(
                context,
                watchFaceData.activeColorStyle,
                watchFaceData.ambientColorStyle
            )

            // Applies the user chosen complication color scheme changes. ComplicationDrawables for
            // each of the styles are defined in XML so we need to replace the complication's
            // drawables.
            for ((_, complication) in complicationSlotsManager.complicationSlots) {
                ComplicationDrawable.getDrawable(
                    context,
                    watchFaceColors.complicationStyleDrawableId
                )?.let {
                    (complication.renderer as CanvasComplicationDrawable).drawable = it
                }
            }
        }
    }

    override fun onDestroy() {
        Logger.d(TAG, "onDestroy()")
        scope.cancel("AnalogWatchCanvasRenderer scope clear() request")
        heartRateRepository.onDestroy()
        super.onDestroy()
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)

        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        val backgroundColor = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientBackgroundColor
        } else {
            watchFaceColors.activeBackgroundColor
        }

        canvas.drawColor(backgroundColor)

        // CanvasComplicationDrawable already obeys rendererParameters.
        drawComplications(canvas, zonedDateTime)

        if (renderParameters.watchFaceLayers.contains(WatchFaceLayer.COMPLICATIONS_OVERLAY)) {
            drawClockHands(canvas, bounds, zonedDateTime)
            drawHeartRate(canvas, bounds, zonedDateTime)
            drawDate(canvas, bounds, zonedDateTime)
            drawDay(canvas, bounds, zonedDateTime)
        }

        if (renderParameters.drawMode != DrawMode.AMBIENT &&
            renderParameters.watchFaceLayers.contains(WatchFaceLayer.BASE)
        ) {
            drawBattery(canvas, bounds)
            drawPSTTime(canvas, bounds, zonedDateTime)

            if (watchFaceData.drawHourPips) {
                drawNumberStyleOuterElement(
                    canvas,
                    bounds,
                    watchFaceData.numberRadiusFraction,
                    watchFaceData.numberStyleOuterCircleRadiusFraction,
                    watchFaceColors.activeOuterElementColor,
                    watchFaceData.numberStyleOuterCircleRadiusFraction,
                    watchFaceData.gapBetweenOuterCircleAndBorderFraction
                )
            }
        } else {
            shouldFetchBattery = true
        }
    }

    private fun drawBattery(canvas: Canvas, bounds: Rect) {
        if (shouldFetchBattery) {
            shouldFetchBattery = false
            val batteryManager = context.getSystemService(BATTERY_SERVICE) as BatteryManager
            val batLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel = batLevel / 100f
            Logger.d("debugdilraj", "batteryLevel = $batteryLevel")

        }

        canvas.drawArc(
            /* left = */ bounds.exactCenterX() - batteryRadius,
            /* top = */ bounds.exactCenterY() - batteryRadius,
            /* right = */ bounds.exactCenterX() + batteryRadius,
            /* bottom = */ bounds.exactCenterY() + batteryRadius,
            /* startAngle = */ 45f,
            /* sweepAngle = */ watchFaceData.batterySweepAngle,
            /* useCenter = */ false,
            /* paint = */ clockHandPaint.apply {
                style = Paint.Style.STROKE
                color = watchFaceColors.activeSecondaryColor
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                pathEffect = CornerPathEffect(50f)
            }
        )

        canvas.drawArc(
            /* left = */ bounds.exactCenterX() - batteryRadius,
            /* top = */ bounds.exactCenterY() - batteryRadius,
            /* right = */ bounds.exactCenterX() + batteryRadius,
            /* bottom = */ bounds.exactCenterY() + batteryRadius,
            /* startAngle = */ 135f,
            /* sweepAngle = */ -watchFaceData.batterySweepAngle * batteryLevel,
            /* useCenter = */ false,
            /* paint = */ clockHandPaint.apply {
                style = Paint.Style.STROKE
                color = watchFaceColors.activePrimaryColor
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                pathEffect = CornerPathEffect(50f)
            }
        )
    }

    private fun drawDate(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val date = zonedDateTime.dayOfMonth.toString()

        val rect = Rect()
        customDataElements.getTextBounds(date, 0, date.length, rect)

        val x = bounds.exactCenterX() + canvas.width / 2f - rect.width() - dateRightMargin
        val y = canvas.height / 2f + rect.height() / 2f - rect.bottom

        customDataElements.color = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientSecondaryColor
        } else {
            watchFaceColors.activePrimaryColor
        }

        canvas.withRotation(-30f, bounds.exactCenterX(), bounds.exactCenterY()) {
            canvas.drawText(
                date,
                x,
                y,
                customDataElements,
            )
        }
    }

    private fun drawPSTTime(canvas: Canvas, bounds: Rect, zonedDateTimeLocal: ZonedDateTime) {
        val zonedDateTime = zonedDateTimeLocal.withZoneSameInstant(ZoneId.of("PST"))
        val hour = zonedDateTime.hour.toString()

        val rect = Rect()
        customDataElements.getTextBounds(hour, 0, hour.length, rect)

        val x = canvas.width / 2f + rect.width() / 2f - rect.right
        val y = bounds.exactCenterY() + canvas.height / 2f - rect.height()

        customDataElements.color = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientSecondaryColor
        } else {
            watchFaceColors.activePrimaryColor
        }

        canvas.withRotation(30f, bounds.exactCenterX(), bounds.exactCenterY()) {
            canvas.drawText(
                hour,
                x,
                y,
                customDataElements,
            )
        }

        val minute = zonedDateTime.minute.toString()

        val rectMinute = Rect()
        customDataElements.getTextBounds(minute, 0, minute.length, rectMinute)

        val xMinute = canvas.width / 2f + rectMinute.width() / 2f - rectMinute.right
        val yMinute = bounds.exactCenterY() + canvas.height / 2f - rectMinute.height()

        customDataElements.color = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientSecondaryColor
        } else {
            watchFaceColors.activePrimaryColor
        }

        canvas.withRotation(-30f, bounds.exactCenterX(), bounds.exactCenterY()) {
            canvas.drawText(
                minute,
                xMinute,
                yMinute,
                customDataElements,
            )
        }
    }

    private fun drawDay(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val date = when (zonedDateTime.dayOfWeek) {
            DayOfWeek.MONDAY -> "M"
            DayOfWeek.TUESDAY -> "Tu"
            DayOfWeek.WEDNESDAY -> "W"
            DayOfWeek.THURSDAY -> "Th"
            DayOfWeek.FRIDAY -> "F"
            DayOfWeek.SATURDAY -> "Sa"
            DayOfWeek.SUNDAY -> "Su"
        }

        val rect = Rect()
        customDataElements.getTextBounds(date, 0, date.length, rect)
        val x = bounds.exactCenterX() - canvas.width / 2f + dateRightMargin
        val y = canvas.height / 2f + rect.height() / 2f - rect.bottom
        customDataElements.color = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientSecondaryColor
        } else {
            watchFaceColors.activePrimaryColor
        }
        canvas.withRotation(30f, bounds.exactCenterX(), bounds.exactCenterY()) {
            canvas.drawText(
                date,
                x,
                y,
                customDataElements,
            )
        }
    }

    private fun drawHeartRate(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val hr = heartRateRepository.heartRate.toInt().toString()

        val rect = Rect()
        customDataElements.getTextBounds(hr, 0, hr.length, rect)

        val x = canvas.width / 2f - rect.width() / 2f - rect.left
        customDataElements.color = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientSecondaryColor
        } else {
            watchFaceColors.activePrimaryColor
        }

        canvas.drawText(
            hr,
            x,
            heartRateTopMargin,
            customDataElements,
        )
    }

    // ----- All drawing functions -----
    @OptIn(ComplicationExperimental::class)
    private fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled && renderParameters.drawMode in listOf(DrawMode.INTERACTIVE, DrawMode.LOW_BATTERY_INTERACTIVE)) {

                ComplicationDrawable(context).apply {
                    activeStyle.run {
                        backgroundColor = watchFaceColors.activeBackgroundColor
                        textColor = watchFaceColors.activeSecondaryColor
                        textSize = 100
                        borderColor = Color.TRANSPARENT
                        iconColor = Color.TRANSPARENT
                    }
                    (complication.renderer as CanvasComplicationDrawable).drawable = this
                }
                Logger.d("debugdilraj", "${complication.boundsType} -- ${complication.complicationData.value} -- ${complication.boundingArc} -- ${complication.complicationSlotBounds} -- ")


                complication.render(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    private fun drawClockHands(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime
    ) {
        // Only recalculate bounds (watch face size/surface) has changed or the arm of one of the
        // clock hands has changed (via user input in the settings).
        // NOTE: Watch face surface usually only updates one time (when the size of the device is
        // initially broadcasted).
        if (currentWatchFaceSize != bounds || armLengthChangedRecalculateClockHands) {
            armLengthChangedRecalculateClockHands = false
            currentWatchFaceSize = bounds
            recalculateClockHands(bounds)
        }

        // Retrieve current time to calculate location/rotation of watch arms.
        val secondOfDay = zonedDateTime.toLocalTime().toSecondOfDay()

        // Determine the rotation of the hour and minute hand.

        // Determine how many seconds it takes to make a complete rotation for each hand
        // It takes the hour hand 12 hours to make a complete rotation
        val secondsPerHourHandRotation = Duration.ofHours(12).seconds
        // It takes the minute hand 1 hour to make a complete rotation
        val secondsPerMinuteHandRotation = Duration.ofHours(1).seconds

        // Determine the angle to draw each hand expressed as an angle in degrees from 0 to 360
        // Since each hand does more than one cycle a day, we are only interested in the remainder
        // of the secondOfDay modulo the hand interval
        val hourRotation = secondOfDay.rem(secondsPerHourHandRotation) * 360.0f /
            secondsPerHourHandRotation
//        val minuteRotation = secondOfDay.rem(secondsPerMinuteHandRotation) * 360.0f /
//            secondsPerMinuteHandRotation
        val minuteRotation = zonedDateTime.minute / 60f * 360f

        canvas.withScale(
            x = WATCH_HAND_SCALE,
            y = WATCH_HAND_SCALE,
            pivotX = bounds.exactCenterX(),
            pivotY = bounds.exactCenterY()
        ) {
            val drawAmbient = renderParameters.drawMode == DrawMode.AMBIENT

            clockHandPaint.style =
                if (drawAmbient) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
            clockHandPaint.color = if (drawAmbient) {
                watchFaceColors.ambientPrimaryColor
            } else {
                watchFaceColors.activePrimaryColor
            }

//            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), 2f, clockHandPaint)

            // Draw hour hand.
            withRotation(hourRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                drawPath(hourHandBorder, clockHandPaint)
            }

            // Draw minute hand.
            withRotation(minuteRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                drawPath(minuteHandBorder, clockHandPaint)
            }

            // Draw second hand if not in ambient mode
            if (!drawAmbient) {
                clockHandPaint.color = watchFaceColors.activeSecondaryColor

                // Second hand has a different color style (secondary color) and is only drawn in
                // active mode, so we calculate it here (not above with others).
                val secondsPerSecondHandRotation = Duration.ofMinutes(1).seconds
                val secondsRotation = secondOfDay.rem(secondsPerSecondHandRotation) * 360.0f /
                    secondsPerSecondHandRotation
                clockHandPaint.color = watchFaceColors.activeSecondaryColor

                withRotation(secondsRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                    drawPath(secondHand, clockHandPaint)
                }
            }
        }
    }

    /*
     * Rarely called (only when watch face surface changes; usually only once) from the
     * drawClockHands() method.
     */
    private fun recalculateClockHands(bounds: Rect) {
        Logger.d(TAG, "recalculateClockHands()")
        hourHandBorder =
            createClockHand(
                bounds,
                watchFaceData.hourHandDimensions.lengthFraction,
                watchFaceData.hourHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.hourHandDimensions.xRadiusRoundedCorners,
                watchFaceData.hourHandDimensions.yRadiusRoundedCorners
            )
        hourHandFill = hourHandBorder

        minuteHandBorder =
            createClockHand(
                bounds,
                watchFaceData.minuteHandDimensions.lengthFraction,
                watchFaceData.minuteHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.minuteHandDimensions.xRadiusRoundedCorners,
                watchFaceData.minuteHandDimensions.yRadiusRoundedCorners
            )
        minuteHandFill = minuteHandBorder

        secondHand =
            createClockHand(
                bounds,
                watchFaceData.secondHandDimensions.lengthFraction,
                watchFaceData.secondHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.secondHandDimensions.xRadiusRoundedCorners,
                watchFaceData.secondHandDimensions.yRadiusRoundedCorners
            )
    }

    /**
     * Returns a round rect clock hand if {@code rx} and {@code ry} equals to 0, otherwise return a
     * rect clock hand.
     *
     * @param bounds The bounds use to determine the coordinate of the clock hand.
     * @param length Clock hand's length, in fraction of {@code bounds.width()}.
     * @param thickness Clock hand's thickness, in fraction of {@code bounds.width()}.
     * @param gapBetweenHandAndCenter Gap between inner side of arm and center.
     * @param roundedCornerXRadius The x-radius of the rounded corners on the round-rectangle.
     * @param roundedCornerYRadius The y-radius of the rounded corners on the round-rectangle.
     */
    private fun createClockHand(
        bounds: Rect,
        length: Float,
        thickness: Float,
        gapBetweenHandAndCenter: Float,
        roundedCornerXRadius: Float,
        roundedCornerYRadius: Float
    ): Path {
        val width = bounds.width()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val left = centerX - thickness / 2 * width
        val top = centerY - (gapBetweenHandAndCenter + length) * width
        val right = centerX + thickness / 2 * width
        val bottom = centerY - gapBetweenHandAndCenter * width
        val path = Path()

        if (roundedCornerXRadius != 0.0f || roundedCornerYRadius != 0.0f) {
            path.addRoundRect(
                left,
                top,
                right,
                bottom,
                roundedCornerXRadius,
                roundedCornerYRadius,
                Path.Direction.CW
            )
        } else {
            path.addRect(
                left,
                top,
                right,
                bottom,
                Path.Direction.CW
            )
        }
        return path
    }

    private fun drawNumberStyleOuterElement(
        canvas: Canvas,
        bounds: Rect,
        numberRadiusFraction: Float,
        outerCircleStokeWidthFraction: Float,
        outerElementColor: Int,
        numberStyleOuterCircleRadiusFraction: Float,
        gapBetweenOuterCircleAndBorderFraction: Float
    ) {
        // Draws text hour indicators (12, 3, 6, and 9).
        val textBounds = Rect()
        textPaint.color = outerElementColor
        for (i in HOUR_MARKS.indices) {
            val rotation = 0.5f * (i + 1).toFloat() * Math.PI
            val dx = sin(rotation).toFloat() * numberRadiusFraction * bounds.width().toFloat()
            val dy = -cos(rotation).toFloat() * numberRadiusFraction * bounds.width().toFloat()
            textPaint.getTextBounds(HOUR_MARKS[i], 0, HOUR_MARKS[i].length, textBounds)
            canvas.drawText(
                HOUR_MARKS[i],
                bounds.exactCenterX() + dx - textBounds.width() / 2.0f,
                bounds.exactCenterY() + dy + textBounds.height() / 2.0f,
                textPaint
            )
        }

        // Draws dots for the remain hour indicators between the numbers above.
        outerElementPaint.strokeWidth = outerCircleStokeWidthFraction * bounds.width()
        outerElementPaint.color = outerElementColor
        canvas.save()
        for (i in 0 until 12) {
            outerElementPaint.color = outerElementColor
            if (i % 3 != 0 && i !in INFORMATION_MARKS) {
                drawTopMiddleCircle(
                    canvas,
                    bounds,
                    numberStyleOuterCircleRadiusFraction,
                    gapBetweenOuterCircleAndBorderFraction
                )
            }
            if (i == 1) {
                drawTopMiddleCircle(
                    canvas,
                    bounds,
                    numberStyleOuterCircleRadiusFraction,
                    gapBetweenOuterCircleAndBorderFraction,
                    paint = outerElementPaint
                        .apply {
                            color = when (heartRateRepository.isRecentCallbackReceived) {
                                true -> {
                                    watchFaceColors.indicatorGreen
                                }
                                false -> {
                                    watchFaceColors.indicatorRed
                                }
                                null -> {
                                    watchFaceColors.indicatorYellow
                                }
                            }
                        }
                )
            }
            canvas.rotate(360.0f / 12.0f, bounds.exactCenterX(), bounds.exactCenterY())
        }
        canvas.restore()
    }

    /** Draws the outer circle on the top middle of the given bounds. */
    private fun drawTopMiddleCircle(
        canvas: Canvas,
        bounds: Rect,
        radiusFraction: Float,
        gapBetweenOuterCircleAndBorderFraction: Float,
        paint: Paint = outerElementPaint,
    ) {
        paint.style = Paint.Style.FILL_AND_STROKE

        // X and Y coordinates of the center of the circle.
        val centerX = 0.5f * bounds.width().toFloat()
        val centerY = bounds.width() * (gapBetweenOuterCircleAndBorderFraction + radiusFraction)

        canvas.drawCircle(
            centerX,
            centerY,
            radiusFraction * bounds.width(),
            paint
        )
    }

    companion object {
        private const val TAG = "AnalogWatchCanvasRenderer"

        // Painted between pips on watch face for hour marks.
        private val HOUR_MARKS = arrayOf("3", "6", "9")
        private val INFORMATION_MARKS = arrayOf(1, 2, 10)

        // Used to canvas.scale() to scale watch hands in proper bounds. This will always be 1.0.
        private const val WATCH_HAND_SCALE = 1.0f
    }
}
