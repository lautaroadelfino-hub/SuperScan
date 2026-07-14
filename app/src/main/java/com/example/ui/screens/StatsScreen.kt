package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ReceiptEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(receipts: List<ReceiptEntity>, prefs: Double, onUpdateBudget: (Double) -> Unit) {
    var dateFilter by remember { mutableStateOf("Este Mes") }
    var storeFilter by remember { mutableStateOf("Todos") }
    var categoryFilter by remember { mutableStateOf("Todas") }
    
    val allStores = remember(receipts) { listOf("Todos") + receipts.map { it.storeName }.distinct().sorted() }
    val allCategories = remember(receipts) { listOf("Todas") + receipts.flatMap { it.items }.map { it.category }.distinct().sorted() }
    val dateOptions = listOf("Todo", "Este Mes", "Mes Anterior", "Últimos 3 Meses")
    
    // Time helpers
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val now = remember { Calendar.getInstance() }
    val currentMonth = now.get(Calendar.MONTH)
    val currentYear = now.get(Calendar.YEAR)
    val lastMonthCal = remember { Calendar.getInstance().apply { add(Calendar.MONTH, -1) } }
    val lastMonth = lastMonthCal.get(Calendar.MONTH)
    val lastMonthYear = lastMonthCal.get(Calendar.YEAR)
    val threeMonthsAgo = remember { Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis }
    
    fun parseDate(dateStr: String): Long {
        return try { sdf.parse(dateStr)?.time ?: 0L } catch(e:Exception) { 0L }
    }
    
    val filteredReceipts = remember(receipts, dateFilter, storeFilter, categoryFilter) {
        receipts.filter {
            val t = parseDate(it.date)
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val m = cal.get(Calendar.MONTH)
            val y = cal.get(Calendar.YEAR)
            
            val dateOk = when(dateFilter) {
                "Este Mes" -> m == currentMonth && y == currentYear
                "Mes Anterior" -> m == lastMonth && y == lastMonthYear
                "Últimos 3 Meses" -> t >= threeMonthsAgo
                else -> true
            }
            val storeOk = storeFilter == "Todos" || it.storeName == storeFilter
            dateOk && storeOk
        }.map { r ->
            if (categoryFilter == "Todas") r
            else r.copy(items = r.items.filter { it.category == categoryFilter })
        }.filter { it.items.isNotEmpty() }
    }
    
    val filteredSpent = filteredReceipts.flatMap { it.items }.sumOf { it.totalPrice }
    val filteredTickets = filteredReceipts.size
    
    val currentMonthSpent = remember(receipts, storeFilter, categoryFilter) {
        receipts.filter {
            val c = Calendar.getInstance().apply { timeInMillis = parseDate(it.date) }
            c.get(Calendar.MONTH) == currentMonth && c.get(Calendar.YEAR) == currentYear &&
            (storeFilter == "Todos" || it.storeName == storeFilter)
        }.flatMap { r -> if (categoryFilter == "Todas") r.items else r.items.filter { it.category == categoryFilter } }
         .sumOf { it.totalPrice }
    }
    
    val lastMonthSpent = remember(receipts, storeFilter, categoryFilter) {
        receipts.filter {
            val c = Calendar.getInstance().apply { timeInMillis = parseDate(it.date) }
            c.get(Calendar.MONTH) == lastMonth && c.get(Calendar.YEAR) == lastMonthYear &&
            (storeFilter == "Todos" || it.storeName == storeFilter)
        }.flatMap { r -> if (categoryFilter == "Todas") r.items else r.items.filter { it.category == categoryFilter } }
         .sumOf { it.totalPrice }
    }
    
    val variation = if (lastMonthSpent > 0) ((currentMonthSpent - lastMonthSpent) / lastMonthSpent) * 100 else 0.0
    
    val topProducts = remember(filteredReceipts) {
        filteredReceipts.flatMap { it.items }
            .groupBy { it.productName }
            .map { (name, items) -> ProductStat(name, items.first().category, items.sumOf { it.quantity }) }
            .sortedByDescending { it.quantity }
            .take(15)
    }
    
    val byCategory = remember(filteredReceipts) {
        filteredReceipts.flatMap { it.items }.groupBy { it.category }.mapValues { it.value.sumOf { i -> i.totalPrice } }
    }
    
    val bySupermarket = remember(filteredReceipts) {
        filteredReceipts.groupBy { it.storeName }.mapValues { it.value.sumOf { r -> r.totalAmount } }
    }
    
    val colors = listOf(Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFFC107), Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFFFF5722))

    var expandedProduct by remember { mutableStateOf<String?>(null) }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Explorar Datos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        
        // Filters
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu("Fecha", dateOptions, dateFilter) { dateFilter = it }
            FilterMenu("Comercio", allStores, storeFilter) { storeFilter = it }
            FilterMenu("Categoría", allCategories, categoryFilter) { categoryFilter = it }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Gasto", style = MaterialTheme.typography.labelSmall)
                    Text("$${String.format(Locale.US, "%.0f", filteredSpent)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Vs Mes Ant.", style = MaterialTheme.typography.labelSmall)
                    val sign = if (variation > 0) "+" else ""
                    Text("$sign${String.format(Locale.US, "%.1f", variation)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (variation > 0) Color(0xFFD32F2F) else Color(0xFF388E3C))
                }
            }
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Tickets", style = MaterialTheme.typography.labelSmall)
                    Text("$filteredTickets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text("Productos Más Comprados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (topProducts.isEmpty()) {
                item { Text("No hay productos.", style = MaterialTheme.typography.bodyMedium) }
            }
            
            items(topProducts, key = { "prod_${it.productName}" }) { product ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateItem().clickable {
                        expandedProduct = if (expandedProduct == product.productName) null else product.productName
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product.productName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("${product.category} • ${product.quantity} comprados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(
                                imageVector = if (expandedProduct == product.productName) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        
                        AnimatedVisibility(
                            visible = expandedProduct == product.productName,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            val observations = remember(receipts, product.productName) {
                                receipts.flatMap { r -> r.items.map { it to r } }
                                    .filter { it.first.productName == product.productName }
                                    .map { (item, r) -> PriceObservation(parseDate(r.date), r.storeName, item.unitPrice) }
                                    .sortedBy { it.date }
                            }
                            ProductPriceChart(observations)
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Gastos por Categoría", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                if (byCategory.isNotEmpty()) {
                    val totalCat = byCategory.values.sum()
                    val chartProgress = remember { Animatable(0f) }
                    LaunchedEffect(byCategory) {
                        chartProgress.snapTo(0f)
                        chartProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(120.dp).padding(8.dp)) {
                            var currentAngle = 0f
                            byCategory.toList().sortedByDescending { it.second }.forEachIndexed { index, pair ->
                                val sweepAngle = ((pair.second / totalCat) * 360).toFloat() * chartProgress.value
                                drawArc(
                                    color = colors[index % colors.size],
                                    startAngle = currentAngle,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    style = Stroke(width = 40f, cap = StrokeCap.Butt)
                                )
                                currentAngle += sweepAngle
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            byCategory.toList().sortedByDescending { it.second }.take(6).forEachIndexed { index, pair ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                    Box(modifier = Modifier.size(12.dp).background(colors[index % colors.size], RoundedCornerShape(2.dp)))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${pair.first}: $${String.format(Locale.US, "%.2f", pair.second)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Text("No hay datos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Gastos por Supermercado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            val maxSupermarket = bySupermarket.values.maxOrNull() ?: 1.0
            if (bySupermarket.isEmpty()) {
                item { Text("No hay datos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(bySupermarket.toList().sortedByDescending { it.second }, key = { "store_${it.first}" }) { (supermarket, total) ->
                val barFraction by animateFloatAsState(
                    targetValue = (total / maxSupermarket).toFloat(),
                    animationSpec = tween(durationMillis = 600),
                    label = "supermarket_bar"
                )
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).animateItem()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(supermarket, style = MaterialTheme.typography.bodyMedium)
                        Text("$${String.format(Locale.US, "%.2f", total)}", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { barFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterMenu(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != options.first(),
            onClick = { expanded = true },
            label = { Text(if (selected == options.first()) label else selected) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

data class ProductStat(val productName: String, val category: String, val quantity: Double)

data class PriceObservation(val date: Long, val storeName: String, val price: Double)

@Composable
fun ProductPriceChart(observations: List<PriceObservation>) {
    if (observations.isEmpty()) return
    val observationsByStore = observations.groupBy { it.storeName }
    val uniqueStores = observationsByStore.keys.toList()
    val storeColors = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFF00ACC1))
    
    val minPrice = observations.minOfOrNull { it.price }?.toFloat() ?: 0f
    val maxPrice = observations.maxOfOrNull { it.price }?.toFloat() ?: 0f
    val minDate = observations.minOfOrNull { it.date } ?: 0L
    val maxDate = observations.maxOfOrNull { it.date } ?: 0L
    
    val priceRange = if (maxPrice == minPrice) 1f else (maxPrice - minPrice)
    val paddedMinPrice = (minPrice - priceRange * 0.1f).coerceAtLeast(0f)
    val paddedMaxPrice = maxPrice + priceRange * 0.1f
    val currentPriceRange = if (paddedMaxPrice == paddedMinPrice) 1f else (paddedMaxPrice - paddedMinPrice)
    
    val dateRange = if (maxDate == minDate) 1L else (maxDate - minDate)
    
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Evolución de Precio", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
            val w = size.width
            val h = size.height
            
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 2f
            )
            
            uniqueStores.forEachIndexed { index, store ->
                val storeObs = observationsByStore[store]?.sortedBy { it.date } ?: emptyList()
                val color = storeColors[index % storeColors.size]
                
                if (storeObs.size > 1) {
                    val path = Path()
                    storeObs.forEachIndexed { i, obs ->
                        val x = if (dateRange == 1L) w/2f else ((obs.date - minDate).toFloat() / dateRange.toFloat()) * w
                        val y = h - ((obs.price.toFloat() - paddedMinPrice) / currentPriceRange) * h
                        
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                
                storeObs.forEach { obs ->
                    val x = if (dateRange == 1L) w/2f else ((obs.date - minDate).toFloat() / dateRange.toFloat()) * w
                    val y = h - ((obs.price.toFloat() - paddedMinPrice) / currentPriceRange) * h
                    drawCircle(color, radius = 6f, center = Offset(x, y))
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$${String.format(Locale.US, "%.2f", minPrice)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$${String.format(Locale.US, "%.2f", maxPrice)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            uniqueStores.forEachIndexed { index, store ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(storeColors[index % storeColors.size], CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(store, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

