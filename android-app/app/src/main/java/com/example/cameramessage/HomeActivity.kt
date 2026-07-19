package com.example.cameramessage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameramessage.databinding.ActivityHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var pollJob: Job? = null
    private lateinit var exceptionHelper: ExceptionDialogHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exceptionHelper = ExceptionDialogHelper(this)

        setupUserGreeting()
        setupNavigations()
        setupActivitiesList()

        binding.btnLogout.setOnClickListener {
            val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        exceptionHelper.startPollingAlerts()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        exceptionHelper.stopPollingAlerts()
    }

    private fun setupUserGreeting() {
        val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", "연구원")
        binding.greetTitle.text = "안녕하세요, ${nickname}님"
    }

    private fun setupNavigations() {
        binding.btnActionIn.setOnClickListener {
            startActivity(Intent(this, CheckinActivity::class.java))
        }

        binding.btnActionOut.setOnClickListener {
            startActivity(Intent(this, CheckoutActivity::class.java))
        }

        binding.navCheckin.setOnClickListener {
            startActivity(Intent(this, CheckinActivity::class.java))
        }

        binding.navCheckout.setOnClickListener {
            startActivity(Intent(this, CheckoutActivity::class.java))
        }

        binding.navShelf.setOnClickListener {
            startActivity(Intent(this, ShelfManageActivity::class.java))
        }
    }

    private fun setupActivitiesList() {
        binding.activityRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.activityRecyclerView.adapter = ActivitiesAdapter()
    }

    private fun startPolling() {
        pollJob = lifecycleScope.launch {
            while (true) {
                try {
                    // 1. Fetch unregistered devices
                    val shelves = NetworkClient.api.getShelves()
                    val unregistered = shelves.filter { it.status == "unregistered" }
                    if (unregistered.isNotEmpty()) {
                        val device = unregistered.first()
                        binding.bannerDeviceText.text = "Device ID : ${device.id} · 터치하여 선반 등록 화면으로 이동"
                        binding.unregisteredBanner.visibility = View.VISIBLE
                        
                        binding.unregisteredBanner.setOnClickListener {
                            startActivity(Intent(this@HomeActivity, ShelfManageActivity::class.java))
                        }
                        binding.btnBannerGo.setOnClickListener {
                            startActivity(Intent(this@HomeActivity, ShelfManageActivity::class.java))
                        }
                    } else {
                        binding.unregisteredBanner.visibility = View.GONE
                    }

                    // 2. Fetch logs
                    val logs = NetworkClient.api.getLogs()
                    val adapter = binding.activityRecyclerView.adapter as? ActivitiesAdapter
                    adapter?.updateData(logs.take(5)) // take top 5
                } catch (e: Exception) {
                    // Ignore background network issues
                }
                delay(3000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    private class ActivitiesAdapter : RecyclerView.Adapter<ActivitiesAdapter.ViewHolder>() {
        private var items = listOf<TransactionLog>()

        fun updateData(newItems: List<TransactionLog>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_activity, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.chemNameText.text = item.chemical_name
            
            // Format details: "김연구 · 방금 전"
            // Let's print operator and time
            val timeString = item.timestamp.substring(11, 16) // e.g. "14:32"
            holder.infoText.text = "${item.operator_name} · 오늘 $timeString"
            
            holder.badgeText.text = item.action
            if (item.action == "반출") {
                holder.badgeText.setBackgroundResource(R.drawable.badge_primary_bg) // we can set danger tint
                holder.badgeText.setTextColor(0xFFED5565.toInt())
            } else {
                holder.badgeText.setBackgroundResource(R.drawable.badge_primary_bg)
                holder.badgeText.setTextColor(0xFF3D6BF5.toInt())
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val badgeText: TextView = view.findViewById(R.id.activityBadge)
            val chemNameText: TextView = view.findViewById(R.id.activityChemicalName)
            val infoText: TextView = view.findViewById(R.id.activityOperatorInfo)
        }
    }
}
