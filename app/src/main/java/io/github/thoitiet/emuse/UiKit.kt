package io.github.thoitiet.emuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Shared Miuix/HyperOS-style UI builders (plain Views, no Compose).
 * Theme tokens + row builders extracted from MainActivity so every screen
 * (Home / Tools / Permissions / Settings) shares the same look.
 */
object UiKit {

    // ---- theme tokens (Miuix-like) ----
    fun dark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun bg(ctx: Context): Int = if (dark(ctx)) 0xFF0D0D0D.toInt() else 0xFFF5F5F5.toInt()
    fun cardBg(ctx: Context): Int = if (dark(ctx)) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    fun fgColor(ctx: Context): Int = if (dark(ctx)) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
    fun secondary(ctx: Context): Int = if (dark(ctx)) 0xFF9E9E9E.toInt() else 0xFF8E8E93.toInt()
    fun dividerColor(ctx: Context): Int = if (dark(ctx)) 0x14FFFFFF else 0x12000000
    val accent: Int = 0xFF0B84FF.toInt()
    val green: Int = 0xFF4CAF50.toInt()
    val orange: Int = 0xFFFF9800.toInt()
    val red: Int = 0xFFF44336.toInt()
    val gray: Int = 0xFF9E9E9E.toInt()

    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    // ---- primitives ----

    fun sectionTitle(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(secondary(ctx))
        setPadding(dp(ctx, 16), dp(ctx, 24), dp(ctx, 16), dp(ctx, 8))
    }

    fun hintText(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(secondary(ctx))
        setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), 0)
    }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 20).toFloat()
            setColor(cardBg(ctx))
        }
    }

    fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1),
        ).apply { leftMargin = dp(ctx, 16) }
        setBackgroundColor(dividerColor(ctx))
    }

    /** Colored circle with a centered letter, e.g. group/tool icons. */
    fun circleIcon(ctx: Context, letter: String, color: Int, sizeDp: Int = 40) =
        TextView(ctx).apply {
            text = letter
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    fun toast(ctx: Context, t: String) =
        Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    // ---- rows ----

    /** Switch row with a silent-set helper; returns the row handle. */
    class SwitchRow(val switch: Switch, val summary: TextView) {
        private var silent = false
        fun setCheckedNoEvent(checked: Boolean) {
            silent = true
            switch.isChecked = checked
            silent = false
        }

        internal fun isSilent(): Boolean = silent
    }

    fun switchRow(
        ctx: Context,
        parent: LinearLayout,
        title: String,
        summary: String,
        onChange: (Boolean) -> Unit,
    ): SwitchRow {
        lateinit var row: SwitchRow
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor(ctx))
        })
        val summaryView = TextView(ctx).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary(ctx))
        }
        texts.addView(summaryView)
        val sw = Switch(ctx)
        sw.setOnCheckedChangeListener { _, checked ->
            if (!row.isSilent()) onChange(checked)
        }
        rowView.addView(texts)
        rowView.addView(sw)
        parent.addView(rowView)
        row = SwitchRow(sw, summaryView)
        return row
    }

    /** Returns Pair(Button, summary TextView). */
    fun actionRow(
        ctx: Context,
        parent: LinearLayout,
        title: String,
        summary: String,
        buttonText: String,
        onClick: () -> Unit,
    ): Pair<Button, TextView> {
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor(ctx))
        })
        val summaryView = TextView(ctx).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary(ctx))
        }
        texts.addView(summaryView)
        rowView.addView(texts)
        val btn = Button(ctx).apply {
            text = buttonText
            setOnClickListener { onClick() }
        }
        rowView.addView(btn)
        parent.addView(rowView)
        return btn to summaryView
    }

    fun infoRow(ctx: Context, parent: LinearLayout, title: String, value: String) {
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        rowView.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor(ctx))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        rowView.addView(TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary(ctx))
        })
        parent.addView(rowView)
    }

    fun inputRow(
        ctx: Context,
        parent: LinearLayout,
        title: String,
        hint: String,
        password: Boolean,
    ): EditText {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        col.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor(ctx))
        })
        val et = EditText(ctx).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(fgColor(ctx))
            setHintTextColor(secondary(ctx))
            if (password) inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        col.addView(et)
        parent.addView(col)
        return et
    }

    /** Eta-style navigation card: icon circle + title + subtitle + chevron. */
    fun navCard(
        ctx: Context,
        parent: LinearLayout,
        icon: String,
        iconColor: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ) {
        val c = card(ctx)
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            isClickable = true
            isFocusable = true
        }
        rowView.addView(circleIcon(ctx, icon, iconColor, 44))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(ctx, 12), 0, 0, 0)
        }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor(ctx))
        })
        texts.addView(TextView(ctx).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary(ctx))
        })
        rowView.addView(texts)
        rowView.addView(TextView(ctx).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(secondary(ctx))
        })
        rowView.setOnClickListener { onClick() }
        c.addView(rowView)
        parent.addView(c)
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 12),
            )
        }
        parent.addView(spacer)
    }

    // ---- screen scaffolding ----

    /** Standard screen header: big title + subtitle. */
    fun header(ctx: Context, parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(fgColor(ctx))
            setPadding(0, dp(ctx, 24), 0, 0)
        })
        parent.addView(TextView(ctx).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary(ctx))
            setPadding(0, 0, 0, dp(ctx, 16))
        })
    }

    /** Bottom navigation: Trang chủ / Tools / Quyền / Cài đặt. */
    fun addBottomNav(activity: Activity, selected: Int) {
        val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup
            ?: return
        if (content.childCount == 0) return
        val old = content.getChildAt(0)
        content.removeView(old)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg(activity))
            addView(
                old,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                ),
            )
            addView(bottomNavBar(activity, selected))
        }
        content.addView(
            wrap,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private data class NavItem(val label: String, val icon: String, val cls: Class<*>)

    private fun bottomNavBar(activity: Activity, selected: Int): View {
        val items = listOf(
            NavItem("Trang chủ", "⌂", MainActivity::class.java),
            NavItem("Tools", "⚙", ToolsActivity::class.java),
            NavItem("Quyền", "🛡", PermissionsActivity::class.java),
            NavItem("Cài đặt", "☰", SettingsActivity::class.java),
        )
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg(activity))
        }
        bar.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1),
            )
            setBackgroundColor(dividerColor(activity))
        })
        val rowView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 10))
        }
        items.forEachIndexed { i, item ->
            val tab = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
            }
            val sel = i == selected
            tab.addView(TextView(activity).apply {
                text = item.icon
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(if (sel) accent else secondary(activity))
            })
            tab.addView(TextView(activity).apply {
                text = item.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (sel) accent else secondary(activity))
            })
            if (!sel) {
                tab.setOnClickListener {
                    activity.startActivity(
                        Intent(activity, item.cls).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                    )
                }
            }
            rowView.addView(tab)
        }
        bar.addView(rowView)
        return bar
    }
}
