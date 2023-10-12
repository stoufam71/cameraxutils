package com.jds.cameraxutils

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.jds.camerax.CameraXUtils
import com.jds.cameraxutils.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        with(binding) {
            btCamera.setOnClickListener {
                CameraXUtils.newInstance {
                    DetailActivity.newInstance(this@MainActivity, it)
                }.show(supportFragmentManager, CameraXUtils.TAG)
            }
        }
    }
}