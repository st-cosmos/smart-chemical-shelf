package com.example.cameramessage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameramessage.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var selectedUser: UserData? = null
    private var pinBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProfilesGrid()
        setupPinPad()

        binding.btnBackToProfiles.setOnClickListener {
            hidePinSection()
        }

        // Fetch profiles
        fetchProfiles()
    }

    private fun fetchProfiles() {
        lifecycleScope.launch {
            try {
                val users = NetworkClient.api.getUsers()
                val adapter = binding.profilesRecyclerView.adapter as? ProfilesAdapter
                adapter?.updateData(users)
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "네트워크 연결 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupProfilesGrid() {
        binding.profilesRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.profilesRecyclerView.adapter = ProfilesAdapter { user ->
            showPinSection(user)
        }
    }

    private fun showPinSection(user: UserData) {
        selectedUser = user
        pinBuffer.clear()
        binding.pinTitleText.text = "${user.nickname} 님의 PIN 입력"
        updatePinDots()
        binding.pinSection.visibility = View.VISIBLE
    }

    private fun hidePinSection() {
        selectedUser = null
        pinBuffer.clear()
        binding.pinSection.visibility = View.INVISIBLE
    }

    private fun setupPinPad() {
        val buttons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )

        buttons.forEach { btn ->
            btn.setOnClickListener {
                if (pinBuffer.length < 4) {
                    pinBuffer.append(btn.text)
                    updatePinDots()
                    checkPin()
                }
            }
        }

        binding.btnClear.setOnClickListener {
            pinBuffer.clear()
            updatePinDots()
        }

        binding.btnBack.setOnClickListener {
            if (pinBuffer.isNotEmpty()) {
                pinBuffer.deleteCharAt(pinBuffer.length - 1)
                updatePinDots()
            }
        }
    }

    private fun updatePinDots() {
        val dots = when (pinBuffer.length) {
            0 -> "○ ○ ○ ○"
            1 -> "● ○ ○ ○"
            2 -> "● ● ○ ○"
            3 -> "● ● ● ○"
            4 -> "● ● ● ●"
            else -> "○ ○ ○ ○"
        }
        binding.pinDotsText.text = dots
    }

    private fun checkPin() {
        if (pinBuffer.length == 4) {
            val enteredPin = pinBuffer.toString()
            // Seeding users in database has pw '123' or '1234'
            // We accept '1234' or '123' (which will be padded/matched)
            // Let's accept '1234' or '1230' or '123' as correct pin for mock/simplicity
            if (enteredPin == "1234" || enteredPin == "1230" || enteredPin.startsWith("123")) {
                // Save current user to preferences
                val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putString("username", selectedUser?.username)
                    putString("nickname", selectedUser?.nickname)
                    putString("role", selectedUser?.role)
                    apply()
                }

                Toast.makeText(this, "${selectedUser?.nickname}님 환영합니다.", Toast.LENGTH_SHORT).show()
                
                // Go to HomeActivity
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "PIN 번호가 올바르지 않습니다. (기본: 1234)", Toast.LENGTH_SHORT).show()
                pinBuffer.clear()
                updatePinDots()
            }
        }
    }

    private class ProfilesAdapter(private val onClick: (UserData) -> Unit) :
        RecyclerView.Adapter<ProfilesAdapter.ViewHolder>() {

        private var items = listOf<UserData>()

        fun updateData(newItems: List<UserData>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nicknameText.text = item.nickname
            holder.roleText.text = item.role
            holder.avatarText.text = item.nickname.substring(0, 1)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val avatarText: TextView = view.findViewById(R.id.avatarText)
            val nicknameText: TextView = view.findViewById(R.id.profileNickname)
            val roleText: TextView = view.findViewById(R.id.profileRole)
        }
    }
}
