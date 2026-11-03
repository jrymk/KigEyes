package com.jerrymk.kigeyes

import android.content.Context
import android.view.View
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Tasks require explicit close while in progress; completed tasks may dismiss naturally. */
internal class SetupSheet(private val context: Context, onAction: ()->Unit, onClose: ()->Unit, onSecondary: ()->Unit = {}) {
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
    private val title=TextView(context).apply { textSize=24f;setTextColor(MaterialColors.getColor(context,com.google.android.material.R.attr.colorOnSurface,"Setup")) }
    private val message=TextView(context).apply { textSize=16f;setPadding(0,dp(16),0,dp(20)) }
    private val progress=LinearProgressIndicator(context).apply { isIndeterminate=true }
    private val action=MaterialButton(context).apply { isAllCaps=false;minHeight=dp(52);setOnClickListener { onAction() } }
    private val secondary=MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        isAllCaps=false;setOnClickListener { onSecondary() }
    }
    private val dialog=BottomSheetDialog(context)
    private var dismissible=false
    val isShowing get()=dialog.isShowing
    init {
        val body=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(16),dp(24),dp(24))
            addView(LinearLayout(context).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                addView(title,LinearLayout.LayoutParams(0,-2,1f))
                addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    icon=context.getDrawable(R.drawable.ic_home_x);iconPadding=0;iconSize=dp(20)
                    setPadding(dp(12),0,dp(12),0);minWidth=dp(48);minimumWidth=dp(48)
                    contentDescription="Close setup";tooltipText="Close setup";setOnClickListener { onClose();dismiss() }
                },LinearLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(8) })
            },LinearLayout.LayoutParams(-1,-2))
            addView(message);addView(progress,LinearLayout.LayoutParams(-1,dp(4)))
            addView(action,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
            addView(secondary,LinearLayout.LayoutParams(-1,-2))
        }
        dialog.setContentView(ScrollView(context).apply { addView(body) })
        dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onClose() }
        dialog.behavior.apply { isDraggable=false;skipCollapsed=true;state=BottomSheetBehavior.STATE_EXPANDED }
    }
    fun show()=dialog.show()
    fun dismiss()=dialog.dismiss()
    fun render(heading: String,detail: String,label: String,busy: Boolean=false,enabled: Boolean=true,secondaryLabel: String="",completed: Boolean=false) {
        if(dismissible!=completed) {
            dismissible=completed
            dialog.setCancelable(completed);dialog.setCanceledOnTouchOutside(completed)
            dialog.behavior.isDraggable=completed
        }
        title.text=heading;message.text=detail;action.text=label;action.isEnabled=enabled
        progress.visibility=if(busy)View.VISIBLE else View.GONE
        secondary.text=secondaryLabel;secondary.visibility=if(secondaryLabel.isEmpty())View.GONE else View.VISIBLE
    }
}
