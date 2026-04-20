package co.rivium.push.example

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.rivium.push.example.databinding.ActivityLogViewerBinding
import co.rivium.push.example.databinding.ItemLogEntryBinding

/**
 * Log Viewer Activity for debugging.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Logs"
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        val logs = intent.getStringArrayListExtra("logs") ?: arrayListOf()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = LogAdapter(logs)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener {
            (binding.recyclerView.adapter as? LogAdapter)?.clear()
        }

        binding.btnShare.setOnClickListener {
            val logs = intent.getStringArrayListExtra("logs") ?: arrayListOf()
            val shareText = logs.joinToString("\n")
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
        }

        binding.btnCopy.setOnClickListener {
            val logs = intent.getStringArrayListExtra("logs") ?: arrayListOf()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Rivium Push Logs", logs.joinToString("\n"))
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

class LogAdapter(private val logs: MutableList<String>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemLogEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.tvLog.text = logs[position]
    }

    override fun getItemCount() = logs.size

    fun clear() {
        logs.clear()
        notifyDataSetChanged()
    }
}
