package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppScreen
import com.example.ui.PaponViewModel
import com.example.ui.ShopConfig
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusSuccess
import com.example.util.Formatters

@Composable
fun ReceiptScreen(
    viewModel: PaponViewModel,
    config: ShopConfig,
    onNewSale: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sale by viewModel.lastCompletedSale.collectAsState()
    val saleItems by viewModel.lastCompletedSaleItems.collectAsState()

    if (sale == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("কোনো রসিদ পাওয়া যায়নি")
        }
        return
    }

    val currentSale = sale!!

    // WhatsApp / SMS Receipt Text Generator
    fun generateShareableReceiptText(): String {
        val sb = StringBuilder()
        sb.appendLine("🧾 *${config.shopName}*")
        sb.appendLine("${config.shopAddress} | ${config.shopPhone}")
        sb.appendLine("-----------------------------")
        sb.appendLine("ইনভয়েস: ${currentSale.invoiceNo}")
        sb.appendLine("তারিখ: ${Formatters.formatDateTime(currentSale.saleDate, config.useBengaliNumerals)}")
        if (!currentSale.customerName.isNullOrBlank()) {
            sb.appendLine("ক্রেতা: ${currentSale.customerName}")
        }
        sb.appendLine("-----------------------------")
        for ((idx, it) in saleItems.withIndex()) {
            sb.appendLine("${idx + 1}. ${it.productName}")
            sb.appendLine("   ${Formatters.formatQty(it.qty, it.unitName, config.useBengaliNumerals)} × ${Formatters.formatMoney(it.unitPricePoisha, config.useBengaliNumerals, config.currencySymbol)} = ${Formatters.formatMoney(it.lineTotalPoisha, config.useBengaliNumerals, config.currencySymbol)}")
        }
        sb.appendLine("-----------------------------")
        sb.appendLine("*সর্বমোট:* ${Formatters.formatMoney(currentSale.totalPoisha, config.useBengaliNumerals, config.currencySymbol)}")
        sb.appendLine("পরিশোধ: ${Formatters.formatMoney(currentSale.paidAmountPoisha, config.useBengaliNumerals, config.currencySymbol)}")
        if (currentSale.dueAmountPoisha > 0) {
            sb.appendLine("*বাকি:* ${Formatters.formatMoney(currentSale.dueAmountPoisha, config.useBengaliNumerals, config.currencySymbol)}")
        }
        sb.appendLine("-----------------------------")
        sb.appendLine("ধন্যবাদ! আবার আসবেন।")
        sb.appendLine("পাপন শপ অ্যাপ দ্বারা প্রস্তুতকৃত")
        return sb.toString()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        item {
            // Success Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(StatusSuccess.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("বিক্রয় সফলভাবে সংরক্ষিত হয়েছে!", color = StatusSuccess, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 58mm Thermal Paper Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = config.shopName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = config.shopAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "মোবাইল: ${config.shopPhone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = config.tagline,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color.LightGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ইনভয়েস: ${currentSale.invoiceNo}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(Formatters.formatDateTime(currentSale.saleDate, config.useBengaliNumerals), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (!currentSale.customerName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ক্রেতা: ${currentSale.customerName}", fontSize = 12.sp)
                            Text("পেমেন্ট: ${if (currentSale.paymentMethod == "cash") "নগদ" else if (currentSale.paymentMethod == "due") "বাকি" else "MFS"}", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color.LightGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Items Table
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("পণ্য ও দর", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                        Text("পরিমাণ", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("মোট", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    for (item in saleItems) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(2f)) {
                                Text(item.productName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${Formatters.formatMoney(item.unitPricePoisha, config.useBengaliNumerals, config.currencySymbol)}/${item.unitName}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                Formatters.formatQty(item.qty, "", config.useBengaliNumerals).trim(),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                Formatters.formatMoney(item.lineTotalPoisha, config.useBengaliNumerals, config.currencySymbol),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = Color.LightGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Calculations
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("মোট মূল্য:", fontSize = 13.sp)
                        Text(Formatters.formatMoney(currentSale.subtotalPoisha, config.useBengaliNumerals, config.currencySymbol), fontSize = 13.sp)
                    }

                    if (currentSale.discountPoisha > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("ছাড়:", fontSize = 13.sp, color = StatusDanger)
                            Text("-${Formatters.formatMoney(currentSale.discountPoisha, config.useBengaliNumerals, config.currencySymbol)}", fontSize = 13.sp, color = StatusDanger)
                        }
                    }

                    if (currentSale.vatPoisha > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("ভ্যাট:", fontSize = 13.sp)
                            Text(Formatters.formatMoney(currentSale.vatPoisha, config.useBengaliNumerals, config.currencySymbol), fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("সর্বমোট প্রদেয়:", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            Formatters.formatMoney(currentSale.totalPoisha, config.useBengaliNumerals, config.currencySymbol),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("পরিশোধিত:", fontSize = 13.sp)
                        Text(Formatters.formatMoney(currentSale.paidAmountPoisha, config.useBengaliNumerals, config.currencySymbol), fontSize = 13.sp)
                    }

                    if (currentSale.dueAmountPoisha > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("বাকি রইলো:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StatusDanger)
                            Text(Formatters.formatMoney(currentSale.dueAmountPoisha, config.useBengaliNumerals, config.currencySymbol), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StatusDanger)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("--- ধন্যবাদ! আবার আসবেন ---", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Powered by Papon Shop POS", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Action Buttons Row (Print, Share WhatsApp, SMS)
        item {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Print simulation
                Button(
                    onClick = {
                        viewModel.showToast("৫৮ মিমি থার্মাল প্রিন্টারে কমান্ড পাঠানো হচ্ছে...")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("প্রিন্ট")
                }

                // WhatsApp Share
                Button(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, generateShareableReceiptText())
                            type = "text/plain"
                            setPackage("com.whatsapp")
                        }
                        try {
                            context.startActivity(sendIntent)
                        } catch (e: Exception) {
                            // Fallback to any share intent
                            val fallbackIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, generateShareableReceiptText())
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(fallbackIntent, "রসিদ শেয়ার করুন"))
                        }
                    },
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("হোয়াটসঅ্যাপ", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onGoHome,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("হোমে যান")
                }

                Button(
                    onClick = onNewSale,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("নতুন বিক্রয়")
                }
            }
        }
    }
}
