package com.qchm.xyz.bean

data class SignBean(
    val code: Int,
    val data: ArrayList<SignDataBean>,
    val msg: String
)

data class SignDataBean(
    val day_time_format: Long,
    val day_num: Int,
    val is_today: Int,
    val money: String,
    val sign_id: Int,
    val sign_status: Int
)

