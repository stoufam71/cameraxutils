package com.jds.cameraxutils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.jds.cameraxutils.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUri = intent.getStringExtra("uri")?.toUri()
        Glide.with(this)
            .load(imageUri)
            .into(binding.ivImage)
    }

    companion object {

        fun newInstance(context: Context, uri: Uri) {
            context.startActivity(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", uri.toString())
            })
        }
    }
}