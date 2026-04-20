package co.rivium.push.example

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import co.rivium.push.example.databinding.ActivityMessageDetailBinding

/**
 * Message Detail Activity showing full notification details.
 */
class MessageDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        displayMessage()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Message Details"
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun displayMessage() {
        val title = intent.getStringExtra("title") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val data = intent.getStringExtra("data")
        val imageUrl = intent.getStringExtra("imageUrl")
        val deepLink = intent.getStringExtra("deepLink")

        binding.tvTitle.text = title
        binding.tvBody.text = body

        // Data
        if (data != null) {
            binding.cardData.visibility = View.VISIBLE
            binding.tvData.text = data
        } else {
            binding.cardData.visibility = View.GONE
        }

        // Image
        if (imageUrl != null) {
            binding.cardImage.visibility = View.VISIBLE
            Glide.with(this)
                .load(imageUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(binding.ivImage)
        } else {
            binding.cardImage.visibility = View.GONE
        }

        // Deep Link
        if (deepLink != null) {
            binding.cardDeepLink.visibility = View.VISIBLE
            binding.tvDeepLink.text = deepLink
        } else {
            binding.cardDeepLink.visibility = View.GONE
        }
    }
}
