package com.universalproductscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.universalproductscanner.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var db: ProductDb
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient()
    @Volatile private var lastScanned = ""

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else binding.statusText.text = "ক্যামেরা Permission দেওয়া হয়নি"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = ProductDb(this)
        binding.scanButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.searchButton.setOnClickListener { searchProduct() }
        binding.saveButton.setOnClickListener { saveProduct() }
    }

    private fun startCamera() {
        binding.previewView.visibility = View.VISIBLE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val scanner = BarcodeScanning.getClient()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val value = codes.firstOrNull()?.rawValue
                        if (!value.isNullOrBlank() && value != lastScanned) {
                            lastScanned = value
                            runOnUiThread {
                                binding.barcodeInput.setText(value)
                                binding.statusText.text = "Barcode পাওয়া গেছে"
                                searchProduct()
                            }
                        }
                    }.addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                binding.statusText.text = "Barcode ক্যামেরার সামনে ধরুন"
            } catch (e: Exception) {
                binding.statusText.text = "Camera চালু করা যায়নি: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun searchProduct() {
        val barcode = binding.barcodeInput.text.toString().trim()
        if (barcode.isEmpty()) { binding.statusText.text = "আগে Barcode লিখুন বা Scan করুন"; return }
        val local = db.find(barcode)
        if (local != null) { showProduct(local, "নিজের Database"); return }
        binding.statusText.text = "নিজের Database-এ নেই, Online-এ খোঁজা হচ্ছে..."
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json")
                    .header("User-Agent", "UniversalProductScanner/1.0").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val product = json.optJSONObject("product")
                    val name = product?.optString("product_name", "")?.trim().orEmpty()
                    runOnUiThread {
                        if (json.optInt("status") == 1 && name.isNotBlank()) {
                            binding.nameInput.setText(name)
                            binding.priceInput.setText("")
                            binding.resultText.text = "পণ্যের নাম: $name\nBarcode: $barcode\nদাম: নিজের দোকানের দাম লিখুন\nSource: Open Food Facts"
                            binding.statusText.text = "Online-এ পণ্য পাওয়া গেছে"
                        } else {
                            binding.resultText.text = "Online-এ পণ্য পাওয়া যায়নি। নাম ও দাম লিখে Save করতে পারেন।"
                            binding.statusText.text = "পণ্য পাওয়া যায়নি"
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.statusText.text = "Online search ব্যর্থ: Internet সংযোগ পরীক্ষা করুন"
                    binding.resultText.text = "নিজের Database ও Online—কোনোটিতেই পণ্য পাওয়া যায়নি।"
                }
            }
        }.start()
    }

    private fun showProduct(product: Product, source: String) {
        binding.nameInput.setText(product.name)
        binding.priceInput.setText(product.price)
        binding.resultText.text = "পণ্যের নাম: ${product.name}\nBarcode: ${product.barcode}\nদাম: ${product.price} IQD\nSource: $source"
        binding.statusText.text = "নিজের Database থেকে পণ্য পাওয়া গেছে"
    }

    private fun saveProduct() {
        val barcode = binding.barcodeInput.text.toString().trim()
        val name = binding.nameInput.text.toString().trim()
        val price = binding.priceInput.text.toString().trim()
        if (barcode.isEmpty() || name.isEmpty() || price.isEmpty()) {
            Toast.makeText(this, "Barcode, নাম ও দাম পূরণ করুন", Toast.LENGTH_SHORT).show(); return
        }
        db.save(Product(barcode, name, price))
        showProduct(Product(barcode, name, price), "নিজের Database")
        Toast.makeText(this, "পণ্য Save হয়েছে", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        db.close()
    }
}
