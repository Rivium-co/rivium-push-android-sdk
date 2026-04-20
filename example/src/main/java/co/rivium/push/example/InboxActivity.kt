package co.rivium.push.example

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import co.rivium.push.example.databinding.ActivityInboxBinding
import co.rivium.push.example.databinding.ItemInboxMessageBinding
import co.rivium.push.sdk.RiviumPush
import co.rivium.push.sdk.inbox.InboxCallback
import co.rivium.push.sdk.inbox.InboxFilter
import co.rivium.push.sdk.inbox.InboxMessage
import co.rivium.push.sdk.inbox.InboxMessageStatus

/**
 * Inbox Activity demonstrating the Inbox feature.
 *
 * Features demonstrated:
 * - Fetching inbox messages with pagination
 * - Filtering by status (unread, read, archived)
 * - Marking messages as read
 * - Archiving and deleting messages
 * - Bulk operations
 * - Pull-to-refresh
 * - Unread count display
 */
class InboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInboxBinding
    private val adapter = InboxAdapter()
    private var currentFilter: InboxMessageStatus? = null  // null = show all messages
    private var currentOffset = 0
    private val pageSize = 20
    private var isLoading = false
    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupSwipeRefresh()
        setupInboxCallback()

        loadMessages(refresh = true)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Inbox"
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        adapter.onItemClick = { message ->
            showMessageDetail(message)
        }

        adapter.onMarkAsRead = { message ->
            markAsRead(message)
        }

        adapter.onArchive = { message ->
            archiveMessage(message)
        }

        adapter.onDelete = { message ->
            deleteMessage(message)
        }

        // Pagination - load more when reaching end
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && hasMore) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5) {
                        loadMore()
                    }
                }
            }
        })
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener {
            currentFilter = null  // Show all messages (no status filter)
            loadMessages(refresh = true)
        }
        binding.chipUnread.setOnClickListener {
            currentFilter = InboxMessageStatus.UNREAD
            loadMessages(refresh = true)
        }
        binding.chipRead.setOnClickListener {
            currentFilter = InboxMessageStatus.READ
            loadMessages(refresh = true)
        }
        binding.chipArchived.setOnClickListener {
            currentFilter = InboxMessageStatus.ARCHIVED
            loadMessages(refresh = true)
        }

        binding.btnMarkAllRead.setOnClickListener {
            markAllAsRead()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            loadMessages(refresh = true)
        }
    }

    private fun setupInboxCallback() {
        RiviumPush.setInboxCallback(object : InboxCallback {
            override fun onMessageReceived(message: InboxMessage) {
                runOnUiThread {
                    Toast.makeText(this@InboxActivity, "New message: ${message.content.title}", Toast.LENGTH_SHORT).show()
                    loadMessages(refresh = true)
                }
            }

            override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) {
                runOnUiThread {
                    loadMessages(refresh = true)
                }
            }
        })
    }

    private fun loadMessages(refresh: Boolean) {
        if (refresh) {
            currentOffset = 0
            hasMore = true
        }

        if (isLoading) return
        isLoading = true

        if (refresh) {
            binding.progressBar.visibility = View.VISIBLE
        }

        val filter = InboxFilter(
            status = currentFilter,
            limit = pageSize,
            offset = currentOffset
        )

        android.util.Log.d("InboxActivity", "loadMessages: filter=$filter, refresh=$refresh")
        RiviumPush.getInboxMessages(
            filter = filter,
            onSuccess = { response ->
                android.util.Log.d("InboxActivity", "onSuccess: messages=${response.messages.size}, total=${response.total}, unread=${response.unreadCount}")
                response.messages.take(3).forEach { msg ->
                    android.util.Log.d("InboxActivity", "  Message: id=${msg.id}, title=${msg.content.title}, status=${msg.status}")
                }
                runOnUiThread {
                    isLoading = false
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false

                    android.util.Log.d("InboxActivity", "submitList: refresh=$refresh, currentList=${adapter.currentList.size}, newMessages=${response.messages.size}")
                    if (refresh) {
                        adapter.submitList(response.messages)
                    } else {
                        val newList = adapter.currentList + response.messages
                        adapter.submitList(newList)
                    }
                    android.util.Log.d("InboxActivity", "After submitList: currentList=${adapter.currentList.size}")

                    hasMore = response.messages.size >= pageSize
                    currentOffset += response.messages.size

                    updateUnreadCount(response.unreadCount)
                    updateEmptyState(adapter.currentList.isEmpty())
                    android.util.Log.d("InboxActivity", "isEmpty=${adapter.currentList.isEmpty()}")
                }
            },
            onError = { error ->
                android.util.Log.e("InboxActivity", "onError: $error")
                runOnUiThread {
                    isLoading = false
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun loadMore() {
        loadMessages(refresh = false)
    }

    private fun markAsRead(message: InboxMessage) {
        RiviumPush.markInboxMessageAsRead(
            messageId = message.id,
            onSuccess = {
                runOnUiThread {
                    Toast.makeText(this, "Marked as read", Toast.LENGTH_SHORT).show()
                    loadMessages(refresh = true)
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun archiveMessage(message: InboxMessage) {
        RiviumPush.archiveInboxMessage(
            messageId = message.id,
            onSuccess = {
                runOnUiThread {
                    Toast.makeText(this, "Archived", Toast.LENGTH_SHORT).show()
                    loadMessages(refresh = true)
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun deleteMessage(message: InboxMessage) {
        AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setMessage("Are you sure you want to delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                RiviumPush.deleteInboxMessage(
                    messageId = message.id,
                    onSuccess = {
                        runOnUiThread {
                            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                            loadMessages(refresh = true)
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markAllAsRead() {
        RiviumPush.markAllInboxMessagesAsRead(
            onSuccess = {
                runOnUiThread {
                    Toast.makeText(this, "All messages marked as read", Toast.LENGTH_SHORT).show()
                    loadMessages(refresh = true)
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showMessageDetail(message: InboxMessage) {
        // Mark as read when opened
        if (message.status == InboxMessageStatus.UNREAD) {
            RiviumPush.markInboxMessageAsRead(message.id)
        }

        AlertDialog.Builder(this)
            .setTitle(message.content.title)
            .setMessage(buildString {
                append(message.content.body)
                append("\n\nStatus: ${message.status}")
                append("\nCreated: ${message.createdAt}")
                message.category?.let { append("\nCategory: $it") }
            })
            .setPositiveButton("OK", null)
            .setNeutralButton("Archive") { _, _ -> archiveMessage(message) }
            .setNegativeButton("Delete") { _, _ -> deleteMessage(message) }
            .show()
    }

    private fun updateUnreadCount(count: Int) {
        supportActionBar?.title = if (count > 0) "Inbox ($count)" else "Inbox"
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        RiviumPush.setInboxCallback(null)
    }
}

/**
 * RecyclerView Adapter for inbox messages
 */
class InboxAdapter : ListAdapter<InboxMessage, InboxAdapter.ViewHolder>(DiffCallback()) {

    var onItemClick: ((InboxMessage) -> Unit)? = null
    var onMarkAsRead: ((InboxMessage) -> Unit)? = null
    var onArchive: ((InboxMessage) -> Unit)? = null
    var onDelete: ((InboxMessage) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInboxMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemInboxMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: InboxMessage) {
            binding.tvTitle.text = message.content.title
            binding.tvBody.text = message.content.body
            binding.tvTimestamp.text = message.createdAt

            // Show unread indicator
            binding.viewUnreadIndicator.visibility =
                if (message.status == InboxMessageStatus.UNREAD) View.VISIBLE else View.GONE

            // Load image if available
            message.content.imageUrl?.let { url ->
                binding.ivImage.visibility = View.VISIBLE
                Glide.with(binding.root.context)
                    .load(url)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(binding.ivImage)
            } ?: run {
                binding.ivImage.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick?.invoke(message) }

            binding.root.setOnLongClickListener {
                showContextMenu(message)
                true
            }
        }

        private fun showContextMenu(message: InboxMessage) {
            val context = binding.root.context
            AlertDialog.Builder(context)
                .setTitle("Actions")
                .setItems(arrayOf("Mark as Read", "Archive", "Delete")) { _, which ->
                    when (which) {
                        0 -> onMarkAsRead?.invoke(message)
                        1 -> onArchive?.invoke(message)
                        2 -> onDelete?.invoke(message)
                    }
                }
                .show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<InboxMessage>() {
        override fun areItemsTheSame(oldItem: InboxMessage, newItem: InboxMessage) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: InboxMessage, newItem: InboxMessage) =
            oldItem == newItem
    }
}
