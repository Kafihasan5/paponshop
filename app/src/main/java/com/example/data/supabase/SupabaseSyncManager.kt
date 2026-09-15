package com.example.data.supabase

import android.util.Log
import com.example.data.dao.PaponDao
import com.example.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SyncResult(
    val success: Boolean,
    val pulledProductsCount: Int = 0,
    val pulledSalesCount: Int = 0,
    val message: String = "",
    val configUpdates: Map<String, String> = emptyMap(),
    val isNetworkError: Boolean = false
)

class SupabaseSyncManager(private val dao: PaponDao) {

    private val tag = "SupabaseSync"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return try {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                } catch (e: java.net.UnknownHostException) {
                    if (hostname.contains("supabase.co")) {
                        try {
                            listOf(
                                java.net.InetAddress.getByName("172.64.149.246"),
                                java.net.InetAddress.getByName("104.18.38.10")
                            )
                        } catch (_: Exception) {
                            throw e
                        }
                    } else {
                        throw e
                    }
                }
            }
        })
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = SupabaseConfig.url.trimEnd('/') + "/rest/v1"
    private val apiKey: String get() = SupabaseConfig.anonKey

    private val lastTableHashes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun invalidateTableCache() {
        lastTableHashes.clear()
    }

    private fun createHeaders(upsert: Boolean = true): Map<String, String> {
        val headers = mutableMapOf(
            "apikey" to apiKey,
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        if (upsert) {
            headers["Prefer"] = "resolution=merge-duplicates"
        }
        return headers
    }

    suspend fun postOrUpsert(table: String, jsonPayload: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || baseUrl.isBlank() || jsonPayload == "[]") return@withContext true
        try {
            val requestBuilder = Request.Builder()
                .url("$baseUrl/$table")
                .post(jsonPayload.toRequestBody(jsonMediaType))

            createHeaders(upsert = true).forEach { (k, v) ->
                requestBuilder.addHeader(k, v)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val isSuccess = response.isSuccessful || code == 201 || code == 200 || code == 204
            if (!isSuccess) {
                val errorBody = response.body?.string() ?: ""
                Log.w(tag, "Supabase upsert to $table failed ($code): $errorBody")
            } else {
                Log.d(tag, "Supabase upsert to $table succeeded ($code)")
            }
            response.close()
            isSuccess
        } catch (e: Exception) {
            Log.w(tag, "Supabase network unreachable on $table: ${e.message}")
            false
        }
    }

    @Throws(java.io.IOException::class)
    suspend fun getJsonArray(table: String, onlyIfChanged: Boolean = false): JSONArray? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || baseUrl.isBlank()) return@withContext null
        val request = Request.Builder()
            .url("$baseUrl/$table?select=*")
            .get()
            .apply {
                createHeaders(upsert = false).forEach { (k, v) ->
                    addHeader(k, v)
                }
            }
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val bodyStr = response.body?.string() ?: "[]"
            response.close()
            val hash = bodyStr.hashCode()
            if (onlyIfChanged && lastTableHashes[table] == hash) {
                return@withContext null // Remote data has not changed. Skip Room write to avoid UI lag.
            }
            lastTableHashes[table] = hash
            JSONArray(bodyStr)
        } else {
            val code = response.code
            val err = response.body?.string() ?: ""
            response.close()
            Log.w(tag, "GET $table returned $code: $err")
            null
        }
    }

    /**
     * Pull remote changes from remote database into local Room database (Auto-update).
     * If remote data is unchanged, skips writing to Room so the app remains silky smooth.
     */
    suspend fun pullFromSupabase(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        try {
            var productCount = 0
            var salesCount = 0
            val configMap = mutableMapOf<String, String>()
            val checkChanges = !force

            suspend fun fetchSafely(table: String): JSONArray? {
                return try {
                    getJsonArray(table, onlyIfChanged = checkChanges)
                } catch (e: java.io.IOException) {
                    Log.w(tag, "Server unreachable on $table (${e.javaClass.simpleName}: ${e.message}) - aborting pull")
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "Failed to parse $table: ${e.message}")
                    null
                }
            }

            // 1. Categories
            val categoriesArr = fetchSafely("categories")
            if (categoriesArr != null && categoriesArr.length() > 0) {
                val list = mutableListOf<Category>()
                for (i in 0 until categoriesArr.length()) {
                    val obj = categoriesArr.getJSONObject(i)
                    list.add(
                        Category(
                            id = obj.getLong("id"),
                            nameBn = obj.optString("name_bn", ""),
                            nameEn = obj.optString("name_en", ""),
                            iconName = obj.optString("icon_name", "category"),
                            sortOrder = obj.optInt("sort_order", i)
                        )
                    )
                }
                dao.insertCategories(list)
            }

            // 2. Products
            val productsArr = fetchSafely("products")
            if (productsArr != null && productsArr.length() > 0) {
                val list = mutableListOf<Product>()
                for (i in 0 until productsArr.length()) {
                    val obj = productsArr.getJSONObject(i)
                    list.add(
                        Product(
                            id = obj.getLong("id"),
                            nameBn = obj.optString("name_bn", ""),
                            nameEn = obj.optString("name_en", ""),
                            categoryId = obj.optLong("category_id", 1L),
                            unitName = obj.optString("unit_name", "পিস"),
                            barcode = obj.optString("barcode", ""),
                            purchasePricePoisha = obj.optLong("purchase_price_poisha", 0L),
                            salePricePoisha = obj.optLong("sale_price_poisha", 0L),
                            wholesalePricePoisha = obj.optLong("wholesale_price_poisha", 0L),
                            stockQty = obj.optDouble("stock_qty", 0.0),
                            minStock = obj.optDouble("min_stock", 5.0),
                            expiryDate = if (obj.isNull("expiry_date")) null else obj.optString("expiry_date"),
                            supplierId = if (obj.isNull("supplier_id")) null else obj.optLong("supplier_id"),
                            isActive = obj.optBoolean("is_active", true),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertProducts(list)
                productCount = list.size
            }

            // 3. Customers
            val customersArr = fetchSafely("customers")
            if (customersArr != null && customersArr.length() > 0) {
                val list = mutableListOf<Customer>()
                for (i in 0 until customersArr.length()) {
                    val obj = customersArr.getJSONObject(i)
                    list.add(
                        Customer(
                            id = obj.getLong("id"),
                            name = obj.optString("name", ""),
                            phone = obj.optString("phone", ""),
                            address = if (obj.isNull("address")) null else obj.optString("address"),
                            creditLimitPoisha = obj.optLong("credit_limit_poisha", 0L),
                            isActive = obj.optBoolean("is_active", true),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertCustomers(list)
            }

            // 4. Sales & Sale Items
            val salesArr = fetchSafely("sales")
            if (salesArr != null && salesArr.length() > 0) {
                val list = mutableListOf<Sale>()
                for (i in 0 until salesArr.length()) {
                    val obj = salesArr.getJSONObject(i)
                    list.add(
                        Sale(
                            id = obj.getLong("id"),
                            invoiceNo = obj.optString("invoice_no", ""),
                            customerId = if (obj.isNull("customer_id")) null else obj.optLong("customer_id"),
                            customerName = if (obj.isNull("customer_name")) null else obj.optString("customer_name"),
                            saleDate = obj.optLong("sale_date", System.currentTimeMillis()),
                            subtotalPoisha = obj.optLong("subtotal_poisha", 0L),
                            discountPoisha = obj.optLong("discount_poisha", 0L),
                            vatPoisha = obj.optLong("vat_poisha", 0L),
                            totalPoisha = obj.optLong("total_poisha", 0L),
                            paidAmountPoisha = obj.optLong("paid_amount_poisha", 0L),
                            dueAmountPoisha = obj.optLong("due_amount_poisha", 0L),
                            paymentMethod = obj.optString("payment_method", "cash"),
                            userId = obj.optLong("user_id", 1L),
                            note = if (obj.isNull("note")) null else obj.optString("note"),
                            isReturned = obj.optBoolean("is_returned", false),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertSales(list)
                salesCount = list.size
            }

            val itemsArr = fetchSafely("sale_items")
            if (itemsArr != null && itemsArr.length() > 0) {
                val list = mutableListOf<SaleItem>()
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.getJSONObject(i)
                    list.add(
                        SaleItem(
                            id = obj.getLong("id"),
                            saleId = obj.getLong("sale_id"),
                            productId = obj.optLong("product_id", 0L),
                            productName = obj.optString("product_name", ""),
                            unitName = obj.optString("unit_name", "পিস"),
                            qty = obj.optDouble("qty", 1.0),
                            unitPricePoisha = obj.optLong("unit_price_poisha", 0L),
                            purchasePriceAtSalePoisha = obj.optLong("purchase_price_at_sale_poisha", 0L),
                            discountPoisha = obj.optLong("discount_poisha", 0L),
                            lineTotalPoisha = obj.optLong("line_total_poisha", 0L)
                        )
                    )
                }
                dao.insertSaleItems(list)
            }

            // 5. Customer Ledger
            val ledgerArr = fetchSafely("customer_ledger")
            if (ledgerArr != null && ledgerArr.length() > 0) {
                val list = mutableListOf<CustomerLedger>()
                for (i in 0 until ledgerArr.length()) {
                    val obj = ledgerArr.getJSONObject(i)
                    list.add(
                        CustomerLedger(
                            id = obj.getLong("id"),
                            customerId = obj.getLong("customer_id"),
                            refType = obj.optString("ref_type", "sale"),
                            refId = if (obj.isNull("ref_id")) null else obj.optLong("ref_id"),
                            debitPoisha = obj.optLong("debit_poisha", 0L),
                            creditPoisha = obj.optLong("credit_poisha", 0L),
                            note = if (obj.isNull("note")) null else obj.optString("note"),
                            entryDate = obj.optLong("entry_date", System.currentTimeMillis()),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertCustomerLedgers(list)
            }

            // 6. Expenses
            val expensesArr = fetchSafely("expenses")
            if (expensesArr != null && expensesArr.length() > 0) {
                val list = mutableListOf<Expense>()
                for (i in 0 until expensesArr.length()) {
                    val obj = expensesArr.getJSONObject(i)
                    list.add(
                        Expense(
                            id = obj.getLong("id"),
                            categoryId = obj.optLong("category_id", 1L),
                            categoryName = obj.optString("category_name", ""),
                            amountPoisha = obj.optLong("amount_poisha", 0L),
                            note = if (obj.isNull("note")) null else obj.optString("note"),
                            expenseDate = obj.optLong("expense_date", System.currentTimeMillis()),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertExpenses(list)
            }

            // 7. Suppliers & Purchases
            val suppliersArr = fetchSafely("suppliers")
            if (suppliersArr != null && suppliersArr.length() > 0) {
                val list = mutableListOf<Supplier>()
                for (i in 0 until suppliersArr.length()) {
                    val obj = suppliersArr.getJSONObject(i)
                    list.add(
                        Supplier(
                            id = obj.getLong("id"),
                            name = obj.optString("name", ""),
                            phone = obj.optString("phone", ""),
                            company = if (obj.isNull("company")) null else obj.optString("company"),
                            address = if (obj.isNull("address")) null else obj.optString("address"),
                            isActive = obj.optBoolean("is_active", true),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertSuppliers(list)
            }

            val purchasesArr = fetchSafely("purchases")
            if (purchasesArr != null && purchasesArr.length() > 0) {
                val list = mutableListOf<Purchase>()
                for (i in 0 until purchasesArr.length()) {
                    val obj = purchasesArr.getJSONObject(i)
                    list.add(
                        Purchase(
                            id = obj.getLong("id"),
                            invoiceNo = obj.optString("invoice_no", ""),
                            supplierId = obj.optLong("supplier_id", 0L),
                            supplierName = obj.optString("supplier_name", ""),
                            purchaseDate = obj.optLong("purchase_date", System.currentTimeMillis()),
                            totalPoisha = obj.optLong("total_poisha", 0L),
                            paidAmountPoisha = obj.optLong("paid_amount_poisha", 0L),
                            dueAmountPoisha = obj.optLong("due_amount_poisha", 0L),
                            note = if (obj.isNull("note")) null else obj.optString("note"),
                            createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                dao.insertPurchases(list)
            }

            val purchaseItemsArr = fetchSafely("purchase_items")
            if (purchaseItemsArr != null && purchaseItemsArr.length() > 0) {
                val list = mutableListOf<PurchaseItem>()
                for (i in 0 until purchaseItemsArr.length()) {
                    val obj = purchaseItemsArr.getJSONObject(i)
                    list.add(
                        PurchaseItem(
                            id = obj.getLong("id"),
                            purchaseId = obj.getLong("purchase_id"),
                            productId = obj.optLong("product_id", 0L),
                            productName = obj.optString("product_name", ""),
                            qty = obj.optDouble("qty", 1.0),
                            unitPricePoisha = obj.optLong("purchase_price_poisha", 0L),
                            lineTotalPoisha = obj.optLong("line_total_poisha", 0L)
                        )
                    )
                }
                dao.insertPurchaseItems(list)
            }

            // 8. Remote App Config / Features
            val configArr = fetchSafely("app_config")
            if (configArr != null && configArr.length() > 0) {
                for (i in 0 until configArr.length()) {
                    val obj = configArr.getJSONObject(i)
                    val key = obj.optString("key", "")
                    val value = obj.optString("value", "")
                    if (key.isNotBlank()) {
                        configMap[key] = value
                    }
                }
            }

            SyncResult(
                success = true,
                pulledProductsCount = productCount,
                pulledSalesCount = salesCount,
                message = "সফলভাবে আপডেট সম্পন্ন",
                configUpdates = configMap
            )
        } catch (e: java.io.IOException) {
            Log.w(tag, "Supabase pull skipped (offline/unreachable): ${e.message}")
            SyncResult(
                success = false,
                isNetworkError = true,
                message = "নেটওয়ার্ক অফলাইন বা সার্ভার সংযোগ নেই"
            )
        } catch (e: Exception) {
            Log.w(tag, "Error pulling updates: ${e.message}")
            SyncResult(success = false, isNetworkError = false, message = e.message ?: "আপডেট সম্পন্ন করা যায়নি")
        }
    }

    /**
     * Publish a new version of the app from within the app.
     */
    suspend fun publishAppVersion(versionCode: Int, versionName: String, updateNotes: String, apkUrl: String): Boolean = withContext(Dispatchers.IO) {
        val arr = JSONArray().apply {
            put(JSONObject().apply { put("key", "latest_version_code"); put("value", versionCode.toString()) })
            put(JSONObject().apply { put("key", "latest_version_name"); put("value", versionName) })
            put(JSONObject().apply { put("key", "update_notes"); put("value", updateNotes) })
            put(JSONObject().apply { put("key", "apk_download_url"); put("value", apkUrl) })
        }
        postOrUpsert("app_config", arr.toString())
    }

    /**
     * Push all local Room tables to Supabase.
     */
    suspend fun syncAllLocalToSupabase(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Starting full push to Supabase...")

            // 1. Categories
            val categories = dao.getAllCategoriesSync()
            if (categories.isNotEmpty()) {
                val jsonArr = JSONArray()
                categories.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("name_bn", it.nameBn)
                        put("name_en", it.nameEn)
                        put("icon_name", it.iconName)
                        put("sort_order", it.sortOrder)
                    })
                }
                postOrUpsert("categories", jsonArr.toString())
            }

            // 2. Products
            val products = dao.getAllProductsSync()
            if (products.isNotEmpty()) {
                val jsonArr = JSONArray()
                products.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("name_bn", it.nameBn)
                        put("name_en", it.nameEn)
                        put("category_id", it.categoryId)
                        put("unit_name", it.unitName)
                        put("barcode", it.barcode)
                        put("purchase_price_poisha", it.purchasePricePoisha)
                        put("sale_price_poisha", it.salePricePoisha)
                        put("wholesale_price_poisha", it.wholesalePricePoisha)
                        put("stock_qty", it.stockQty)
                        put("min_stock", it.minStock)
                        put("expiry_date", it.expiryDate ?: JSONObject.NULL)
                        put("supplier_id", it.supplierId ?: JSONObject.NULL)
                        put("is_active", it.isActive)
                        put("created_at", it.createdAt)
                        put("updated_at", it.updatedAt)
                    })
                }
                postOrUpsert("products", jsonArr.toString())
            }

            // 3. Customers
            val customers = dao.getAllCustomersSync()
            if (customers.isNotEmpty()) {
                val jsonArr = JSONArray()
                customers.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("phone", it.phone)
                        put("address", it.address ?: JSONObject.NULL)
                        put("credit_limit_poisha", it.creditLimitPoisha)
                        put("is_active", it.isActive)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("customers", jsonArr.toString())
            }

            // 4. Sales
            val sales = dao.getAllSalesSync()
            if (sales.isNotEmpty()) {
                val jsonArr = JSONArray()
                sales.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("invoice_no", it.invoiceNo)
                        put("customer_id", it.customerId ?: JSONObject.NULL)
                        put("customer_name", it.customerName ?: JSONObject.NULL)
                        put("sale_date", it.saleDate)
                        put("subtotal_poisha", it.subtotalPoisha)
                        put("discount_poisha", it.discountPoisha)
                        put("vat_poisha", it.vatPoisha)
                        put("total_poisha", it.totalPoisha)
                        put("paid_amount_poisha", it.paidAmountPoisha)
                        put("due_amount_poisha", it.dueAmountPoisha)
                        put("payment_method", it.paymentMethod)
                        put("user_id", it.userId)
                        put("note", it.note ?: JSONObject.NULL)
                        put("is_returned", it.isReturned)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("sales", jsonArr.toString())
            }

            // 5. Sale Items
            val saleItems = dao.getAllSaleItemsSync()
            if (saleItems.isNotEmpty()) {
                val jsonArr = JSONArray()
                saleItems.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("sale_id", it.saleId)
                        put("product_id", it.productId)
                        put("product_name", it.productName)
                        put("unit_name", it.unitName)
                        put("qty", it.qty)
                        put("unit_price_poisha", it.unitPricePoisha)
                        put("purchase_price_at_sale_poisha", it.purchasePriceAtSalePoisha)
                        put("discount_poisha", it.discountPoisha)
                        put("line_total_poisha", it.lineTotalPoisha)
                    })
                }
                postOrUpsert("sale_items", jsonArr.toString())
            }

            // 6. Customer Ledger
            val ledgerEntries = dao.getAllCustomerLedgersSync()
            if (ledgerEntries.isNotEmpty()) {
                val jsonArr = JSONArray()
                ledgerEntries.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("customer_id", it.customerId)
                        put("ref_type", it.refType)
                        put("ref_id", it.refId ?: JSONObject.NULL)
                        put("debit_poisha", it.debitPoisha)
                        put("credit_poisha", it.creditPoisha)
                        put("note", it.note ?: JSONObject.NULL)
                        put("entry_date", it.entryDate)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("customer_ledger", jsonArr.toString())
            }

            // 7. Expenses
            val expenses = dao.getAllExpensesSync()
            if (expenses.isNotEmpty()) {
                val jsonArr = JSONArray()
                expenses.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("category_id", it.categoryId)
                        put("category_name", it.categoryName)
                        put("amount_poisha", it.amountPoisha)
                        put("note", it.note ?: JSONObject.NULL)
                        put("expense_date", it.expenseDate)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("expenses", jsonArr.toString())
            }

            // 8. Suppliers
            val suppliers = dao.getAllSuppliersSync()
            if (suppliers.isNotEmpty()) {
                val jsonArr = JSONArray()
                suppliers.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("phone", it.phone)
                        put("company", it.company ?: JSONObject.NULL)
                        put("address", it.address ?: JSONObject.NULL)
                        put("is_active", it.isActive)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("suppliers", jsonArr.toString())
            }

            // 9. Purchases
            val purchases = dao.getAllPurchasesSync()
            if (purchases.isNotEmpty()) {
                val jsonArr = JSONArray()
                purchases.forEach {
                    jsonArr.put(JSONObject().apply {
                        put("id", it.id)
                        put("invoice_no", it.invoiceNo)
                        put("supplier_id", it.supplierId)
                        put("supplier_name", it.supplierName)
                        put("purchase_date", it.purchaseDate)
                        put("total_poisha", it.totalPoisha)
                        put("paid_amount_poisha", it.paidAmountPoisha)
                        put("due_amount_poisha", it.dueAmountPoisha)
                        put("note", it.note ?: JSONObject.NULL)
                        put("created_at", it.createdAt)
                    })
                }
                postOrUpsert("purchases", jsonArr.toString())
            }

            true
        } catch (e: Exception) {
            Log.w(tag, "Error during Supabase push: ${e.message}")
            false
        }
    }

    /**
     * Complete Two-Way Sync (Pull cloud updates, then push local mutations).
     */
    suspend fun syncTwoWay(): SyncResult = withContext(Dispatchers.IO) {
        val pullResult = pullFromSupabase()
        if (pullResult.success || !pullResult.isNetworkError) {
            syncAllLocalToSupabase()
        }
        pullResult
    }

    // --- INSTANT PUSH HELPERS FOR IMMEDIATE ACTION SYNC ---

    suspend fun syncProduct(product: Product) = withContext(Dispatchers.IO) {
        syncProducts(listOf(product))
    }

    suspend fun syncProducts(products: List<Product>) = withContext(Dispatchers.IO) {
        if (products.isEmpty()) return@withContext
        val jsonArr = JSONArray()
        products.forEach { product ->
            jsonArr.put(JSONObject().apply {
                put("id", product.id)
                put("name_bn", product.nameBn)
                put("name_en", product.nameEn)
                put("category_id", product.categoryId)
                put("unit_name", product.unitName)
                put("barcode", product.barcode)
                put("purchase_price_poisha", product.purchasePricePoisha)
                put("sale_price_poisha", product.salePricePoisha)
                put("wholesale_price_poisha", product.wholesalePricePoisha)
                put("stock_qty", product.stockQty)
                put("min_stock", product.minStock)
                put("expiry_date", product.expiryDate ?: JSONObject.NULL)
                put("supplier_id", product.supplierId ?: JSONObject.NULL)
                put("is_active", product.isActive)
                put("created_at", product.createdAt)
                put("updated_at", product.updatedAt)
            })
        }
        postOrUpsert("products", jsonArr.toString())
    }

    suspend fun syncSale(
        sale: Sale,
        items: List<SaleItem>,
        affectedProducts: List<Product> = emptyList()
    ) = withContext(Dispatchers.IO) {
        // 1. Sync Sale record
        val saleJson = JSONArray().put(JSONObject().apply {
            put("id", sale.id)
            put("invoice_no", sale.invoiceNo)
            put("customer_id", sale.customerId ?: JSONObject.NULL)
            put("customer_name", sale.customerName ?: JSONObject.NULL)
            put("sale_date", sale.saleDate)
            put("subtotal_poisha", sale.subtotalPoisha)
            put("discount_poisha", sale.discountPoisha)
            put("vat_poisha", sale.vatPoisha)
            put("total_poisha", sale.totalPoisha)
            put("paid_amount_poisha", sale.paidAmountPoisha)
            put("due_amount_poisha", sale.dueAmountPoisha)
            put("payment_method", sale.paymentMethod)
            put("user_id", sale.userId)
            put("note", sale.note ?: JSONObject.NULL)
            put("is_returned", sale.isReturned)
            put("created_at", sale.createdAt)
        })
        postOrUpsert("sales", saleJson.toString())

        // 2. Sync Sale Items
        if (items.isNotEmpty()) {
            val itemsJson = JSONArray()
            items.forEach {
                itemsJson.put(JSONObject().apply {
                    put("id", it.id)
                    put("sale_id", it.saleId)
                    put("product_id", it.productId)
                    put("product_name", it.productName)
                    put("unit_name", it.unitName)
                    put("qty", it.qty)
                    put("unit_price_poisha", it.unitPricePoisha)
                    put("purchase_price_at_sale_poisha", it.purchasePriceAtSalePoisha)
                    put("discount_poisha", it.discountPoisha)
                    put("line_total_poisha", it.lineTotalPoisha)
                })
            }
            postOrUpsert("sale_items", itemsJson.toString())
        }

        // 3. Immediately sync updated product stock levels to Supabase!
        if (affectedProducts.isNotEmpty()) {
            syncProducts(affectedProducts)
        }
    }

    suspend fun syncSaleReturn(sale: Sale, restockedProducts: List<Product> = emptyList()) = withContext(Dispatchers.IO) {
        val saleJson = JSONArray().put(JSONObject().apply {
            put("id", sale.id)
            put("invoice_no", sale.invoiceNo)
            put("is_returned", true)
        })
        postOrUpsert("sales", saleJson.toString())

        if (restockedProducts.isNotEmpty()) {
            syncProducts(restockedProducts)
        }
    }

    suspend fun syncCustomer(customer: Customer) = withContext(Dispatchers.IO) {
        val jsonArr = JSONArray().put(JSONObject().apply {
            put("id", customer.id)
            put("name", customer.name)
            put("phone", customer.phone)
            put("address", customer.address ?: JSONObject.NULL)
            put("credit_limit_poisha", customer.creditLimitPoisha)
            put("is_active", customer.isActive)
            put("created_at", customer.createdAt)
        })
        postOrUpsert("customers", jsonArr.toString())
    }

    suspend fun syncCustomerLedger(ledger: CustomerLedger) = withContext(Dispatchers.IO) {
        val jsonArr = JSONArray().put(JSONObject().apply {
            put("id", ledger.id)
            put("customer_id", ledger.customerId)
            put("ref_type", ledger.refType)
            put("ref_id", ledger.refId ?: JSONObject.NULL)
            put("debit_poisha", ledger.debitPoisha)
            put("credit_poisha", ledger.creditPoisha)
            put("note", ledger.note ?: JSONObject.NULL)
            put("entry_date", ledger.entryDate)
            put("created_at", ledger.createdAt)
        })
        postOrUpsert("customer_ledger", jsonArr.toString())
    }

    suspend fun syncExpense(expense: Expense) = withContext(Dispatchers.IO) {
        val jsonArr = JSONArray().put(JSONObject().apply {
            put("id", expense.id)
            put("category_id", expense.categoryId)
            put("category_name", expense.categoryName)
            put("amount_poisha", expense.amountPoisha)
            put("note", expense.note ?: JSONObject.NULL)
            put("expense_date", expense.expenseDate)
            put("created_at", expense.createdAt)
        })
        postOrUpsert("expenses", jsonArr.toString())
    }

    suspend fun syncSupplier(supplier: Supplier) = withContext(Dispatchers.IO) {
        val jsonArr = JSONArray().put(JSONObject().apply {
            put("id", supplier.id)
            put("name", supplier.name)
            put("phone", supplier.phone)
            put("company", supplier.company ?: JSONObject.NULL)
            put("address", supplier.address ?: JSONObject.NULL)
            put("is_active", supplier.isActive)
            put("created_at", supplier.createdAt)
        })
        postOrUpsert("suppliers", jsonArr.toString())
    }

    suspend fun syncPurchase(
        purchase: Purchase,
        items: List<PurchaseItem>,
        affectedProducts: List<Product> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val jsonArr = JSONArray().put(JSONObject().apply {
            put("id", purchase.id)
            put("invoice_no", purchase.invoiceNo)
            put("supplier_id", purchase.supplierId)
            put("supplier_name", purchase.supplierName)
            put("purchase_date", purchase.purchaseDate)
            put("total_poisha", purchase.totalPoisha)
            put("paid_amount_poisha", purchase.paidAmountPoisha)
            put("due_amount_poisha", purchase.dueAmountPoisha)
            put("note", purchase.note ?: JSONObject.NULL)
            put("created_at", purchase.createdAt)
        })
        postOrUpsert("purchases", jsonArr.toString())

        if (affectedProducts.isNotEmpty()) {
            syncProducts(affectedProducts)
        }
    }
}
