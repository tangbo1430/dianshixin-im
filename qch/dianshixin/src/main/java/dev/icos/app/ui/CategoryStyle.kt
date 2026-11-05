package dev.icos.app.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.icos.app.data.local.FinanceCategory
import dev.icos.app.domain.Direction
import java.util.UUID

/** Built-in decoration; account colors come from canonical metadata, icons and treatments stay local. */
internal data class CategoryStyle(
    val icon: String,
    val color: String,
    val treatment: String,
)

private val categoryColorPattern = Regex("#[0-9A-Fa-f]{6}")
private val categoryBadgeShape = RoundedCornerShape(10.dp)

internal data class CategoryIconOption(
    val key: String,
    val label: String,
    val symbol: AppSymbol,
)


internal object CategoryStyles {
    private const val PREFIX = "category_style_owner_"
    private val icons =
        listOf(
            CategoryIconOption("DEFAULT", "默认", AppSymbol.GRID),
            CategoryIconOption("FOOD", "餐饮", AppSymbol.FOOD),
            CategoryIconOption("TRANSPORT", "交通", AppSymbol.TRANSPORT),
            CategoryIconOption("HOME", "住房", AppSymbol.HOME),
            CategoryIconOption("HEALTH", "医疗", AppSymbol.HEALTH),
            CategoryIconOption("SHOPPING", "购物", AppSymbol.SHOPPING),
            CategoryIconOption("LEISURE", "娱乐", AppSymbol.LEISURE),
            CategoryIconOption("SALARY", "工资", AppSymbol.WALLET),
            CategoryIconOption("GIFT", "礼物", AppSymbol.GIFT),
            CategoryIconOption("OTHER", "其他", AppSymbol.MORE),
            CategoryIconOption("EDUCATION", "教育", AppSymbol.BOOK),
            CategoryIconOption("INVESTMENT", "投资", AppSymbol.STATISTICS),
            CategoryIconOption("SAVING", "储蓄", AppSymbol.SAVING),
            CategoryIconOption("TRAVEL", "旅行", AppSymbol.LOCATION),
            CategoryIconOption("BILL", "账单", AppSymbol.CALENDAR),
            CategoryIconOption("REFUND", "退款", AppSymbol.REFUND),
            CategoryIconOption("TRANSFER", "转账", AppSymbol.TRANSFER),
            CategoryIconOption("PLAN", "计划", AppSymbol.TEMPLATE),
        )
    private val iconsByKey = icons.associateBy(CategoryIconOption::key)
    private val colors = linkedSetOf("#5965D8", "#D95763", "#E18A35", "#2B9A83", "#8B5AC2", "#397FA8", "#65717D", "#C34B91")
    private val treatments = setOf("SOLID", "SOFT", "OUTLINE")
    private fun defaultIcon(category: FinanceCategory): String {
        val name = category.name.trim()
        return when {
            name.contains("转账") -> "TRANSFER"
            name.contains("餐") || name.contains("美食") || name.contains("饮料") -> "FOOD"
            name.contains("交通") || name.contains("出行") || name.contains("地铁") ||
                name.contains("公交") || name.contains("打车") || name.contains("加油") -> "TRANSPORT"
            name.contains("住房") || name.contains("房租") || name.contains("家居") || name.contains("物业") -> "HOME"
            name.contains("医疗") || name.contains("医药") || name.contains("健康") || name.contains("看病") -> "HEALTH"
            name.contains("购物") || name.contains("日用") || name.contains("服饰") ||
                name.contains("生活用品") -> "SHOPPING"
            name.contains("娱乐") || name.contains("电影") || name.contains("游戏") || name.contains("休闲") -> "LEISURE"
            name.contains("工资") || name.contains("薪资") || name.contains("薪酬") -> "SALARY"
            name.contains("礼物") || name.contains("礼金") || name.contains("红包") -> "GIFT"
            name.contains("教育") || name.contains("学习") || name.contains("学费") -> "EDUCATION"
            name.contains("投资") || name.contains("理财") -> "INVESTMENT"
            name.contains("储蓄") || name.contains("存钱") || name.contains("存款") -> "SAVING"
            name.contains("旅行") || name.contains("旅游") -> "TRAVEL"
            name.contains("账单") || name.contains("缴费") -> "BILL"
            name.contains("退款") || name.contains("报销") || name.contains("返现") -> "REFUND"
            name.contains("计划") || name.contains("预算") -> "PLAN"
            name.contains("其他") -> "OTHER"
            category.direction == Direction.INCOME -> "SALARY"
            else -> "OTHER"
        }
    }

    fun default(category: FinanceCategory): CategoryStyle {
        val color = if (category.direction == Direction.INCOME) "#2B9A83" else "#5965D8"
        return CategoryStyle(defaultIcon(category), color, "SOFT")
    }

    fun icons(): List<CategoryIconOption> = icons

    fun iconSymbol(key: String): AppSymbol = iconsByKey.getValue(key).symbol

    fun colors(): Set<String> = colors

    fun treatments(): Set<String> = treatments


    fun read(
        context: Context,
        owner: UUID,
        category: FinanceCategory,
    ): CategoryStyle {
        val preferences = context.getSharedPreferences(PREFIX + owner, Context.MODE_PRIVATE)
        val prefix = category.id.toString() + "."
        val default = default(category)
        val icon = preferences.getString(prefix + "icon", null).takeIf { it in iconsByKey }
        val color =
            if (category.usesCanonicalColor) {
                category.color?.takeIf { it.matches(categoryColorPattern) }
            } else {
                preferences.getString(prefix + "color", null).takeIf { it in colors }
            }
        val treatment = preferences.getString(prefix + "treatment", null).takeIf { it in treatments }
        return CategoryStyle(icon ?: default.icon, color ?: default.color, treatment ?: default.treatment)
    }

    fun write(
        context: Context,
        owner: UUID,
        category: FinanceCategory,
        style: CategoryStyle,
    ) {
        require(style.icon in iconsByKey && (category.usesCanonicalColor || style.color in colors) && style.treatment in treatments)
        val key = category.id.toString() + "."
        val preferences = context.getSharedPreferences(PREFIX + owner, Context.MODE_PRIVATE)
        check(
            preferences
                .edit()
                .putString(key + "icon", style.icon)
                .putString(key + "color", if (category.usesCanonicalColor) null else style.color)
                .putString(key + "treatment", style.treatment)
                .commit(),
        ) { "分类样式保存失败" }
    }

    fun reset(
        context: Context,
        owner: UUID,
        category: FinanceCategory,
    ) {
        val key = category.id.toString() + "."
        check(
            context
                .getSharedPreferences(PREFIX + owner, Context.MODE_PRIVATE)
                .edit()
                .remove(key + "icon")
                .remove(key + "color")
                .remove(key + "treatment")
                .commit(),
        ) { "分类样式重置失败" }
    }
}

@Composable
internal fun CategoryStyleBadge(
    style: CategoryStyle,
    modifier: Modifier = Modifier,
) {
    val color = remember(style.color) { Color(android.graphics.Color.parseColor(style.color)) }
    val shape = categoryBadgeShape
    val decoration =
        when (style.treatment) {
            "SOLID" -> Modifier.clip(shape).background(color)
            "OUTLINE" -> Modifier.clip(shape).border(BorderStroke(1.dp, color), shape)
            else -> Modifier.clip(shape).background(color.copy(alpha = 0.14f))
        }
    val foreground = if (style.treatment == "SOLID") Color.White else color
    Box(
        modifier.size(30.dp).then(decoration),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(CategoryStyles.iconSymbol(style.icon), tint = foreground)
    }
}

@Composable
internal fun rememberCategoryStyle(
    context: Context,
    owner: UUID,
    category: FinanceCategory,
    revision: Int,
): CategoryStyle =
    remember(owner, category, revision) {
        CategoryStyles.read(context, owner, category)
    }
