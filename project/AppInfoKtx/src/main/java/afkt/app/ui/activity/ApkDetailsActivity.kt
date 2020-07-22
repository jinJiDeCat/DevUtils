package afkt.app.ui.activity

import afkt.app.R
import afkt.app.base.Constants
import afkt.app.base.config.PathConfig
import afkt.app.databinding.ActivityApkDetailsBinding
import afkt.app.module.event.FileDeleteEvent
import afkt.app.ui.adapter.KeyValueAdapter
import afkt.app.utils.EventBusUtils
import afkt.app.utils.ProjectUtils
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import dev.utils.app.AppUtils
import dev.utils.app.HandlerUtils
import dev.utils.app.IntentUtils
import dev.utils.app.ResourceUtils
import dev.utils.app.helper.ViewHelper
import dev.utils.app.info.ApkInfoItem
import dev.utils.app.info.AppInfoUtils
import dev.utils.app.logger.DevLogger
import dev.utils.app.permission.PermissionUtils
import dev.utils.app.toast.ToastTintUtils
import dev.utils.common.FileUtils
import dev.utils.common.StringUtils
import dev.utils.common.thread.DevThreadManager
import java.util.*

class ApkDetailsActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var binding: ActivityApkDetailsBinding

    // = Object =

    private var apkInfoItem: ApkInfoItem? = null // APK 信息 Item

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApkDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        init()
    }

    fun init() {
        try {
            apkInfoItem =
                AppInfoUtils.getApkInfoItem(intent.getStringExtra(Constants.Key.KEY_APK_URI))
        } catch (e: Exception) {
            DevLogger.e(e)
        }
        if (apkInfoItem == null) {
            ToastTintUtils.warning(ResourceUtils.getString(R.string.str_get_apkinfo_fail))
            finish()
            return
        }

        setSupportActionBar(binding.vidAadToolbar)
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            // 给左上角图标的左边加上一个返回的图标
            actionBar.setDisplayHomeAsUpEnabled(true)
            // 对应 ActionBar.DISPLAY_SHOW_TITLE
            actionBar.setDisplayShowTitleEnabled(false)
            // 设置点击事件
            binding.vidAadToolbar.setNavigationOnClickListener {
                finish()
            }
        }

        // 获取 APP 信息
        val appInfoBean = apkInfoItem!!.appInfoBean
        ViewHelper.get()
            .setImageDrawable(binding.vidAadAppIgview, appInfoBean.appIcon) // 设置 app 图标
            .setText(binding.vidAadNameTv, appInfoBean.appName) // 设置 app 名
            .setText(binding.vidAadVnameTv, appInfoBean.versionName) // 设置 app 版本

        binding.vidAadRecy.adapter = KeyValueAdapter(apkInfoItem!!.listKeyValues)
        binding.vidAadInstallTv.setOnClickListener(this)
        binding.vidAadDeleteTv.setOnClickListener(this)
    }

    // ===========
    // = OnClick =
    // ===========

    override fun onClick(v: View) {
        when (v.id) {
            R.id.vid_aad_install_tv -> {
                var sourceDir = apkInfoItem!!.appInfoBean.sourceDir
                if (FileUtils.isFileExists(sourceDir)) {
                    // Android 8.0以上
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (getPackageManager().canRequestPackageInstalls()) {
                            AppUtils.installApp(sourceDir) // 安装 APK
                        } else {
                            PermissionUtils.permission(
                                Manifest.permission.REQUEST_INSTALL_PACKAGES
                            ).callBack(object : PermissionUtils.PermissionCallBack {
                                override fun onGranted() {
                                    AppUtils.installApp(sourceDir) // 安装 APK
                                }

                                override fun onDenied(
                                    grantedList: List<String>,
                                    deniedList: List<String>,
                                    notFoundList: List<String>
                                ) {
                                    var builder = StringBuilder()
                                        .append("申请通过的权限" + Arrays.toString(grantedList.toTypedArray()))
                                        .append(StringUtils.NEW_LINE_STR)
                                        .append("拒绝的权限" + deniedList.toString())
                                        .append(StringUtils.NEW_LINE_STR)
                                        .append("未找到的权限" + notFoundList.toString())
                                    if (deniedList.isNotEmpty()) {
                                        DevLogger.d(builder.toString())
                                        ToastTintUtils.info(ResourceUtils.getString(R.string.str_install_request_tips))
                                        // 跳转设置页面, 开启安装未知应用权限
                                        startActivity(IntentUtils.getLaunchAppInstallPermissionSettingsIntent())
                                    } else {
                                        onGranted()
                                    }
                                }
                            }).request(ApkDetailsActivity@ this)
                        }
                    } else { // 安装 APK
                        AppUtils.installApp(sourceDir)
                    }
                } else {
                    ToastTintUtils.warning(ResourceUtils.getString(R.string.str_file_not_exist))
                }
            }
            R.id.vid_aad_delete_tv -> {
                var sourceDir = apkInfoItem!!.appInfoBean.sourceDir
                if (FileUtils.isFileExists(sourceDir)) {
                    FileUtils.deleteFile(sourceDir)
                    EventBusUtils.post(FileDeleteEvent())
                    ToastTintUtils.success(ResourceUtils.getString(R.string.str_delete_suc))
                } else {
                    ToastTintUtils.warning(ResourceUtils.getString(R.string.str_file_not_exist))
                }
            }
        }
    }

    // ========
    // = Menu =
    // ========

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.bar_menu_apk_info, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.bmpi_share -> {
                try {
                    val intent = Intent()
                    intent.action = Intent.ACTION_SEND
                    intent.type = "*/*"
                    intent.putExtra(
                        Intent.EXTRA_STREAM,
                        Uri.fromFile(
                            FileUtils.getFile(apkInfoItem!!.appInfoBean.sourceDir)
                        )
                    )
                    startActivity(intent)
                } catch (e: java.lang.Exception) {
                    ToastTintUtils.error(ResourceUtils.getString(R.string.str_share_fail))
                }
            }
            R.id.bmpi_export_apk -> {// 提示导出中
                DevThreadManager.getInstance(3).execute {
                    val appInfoBean = apkInfoItem!!.appInfoBean

                    // 应用名_包名_版本名.apk
                    var fileName =
                        appInfoBean.appName + "_" + appInfoBean.appPackName + "_" + appInfoBean.versionName + ".apk"

                    var destFile = FileUtils.getFile(PathConfig.AEP_APK_PATH, fileName)
                    if (destFile.exists()) {
                        ToastTintUtils.success(
                            ResourceUtils.getString(R.string.str_export_suc)
                                    + " " + PathConfig.AEP_APK_PATH + fileName
                        )
                        return@execute
                    }
                    // 提示导出中
                    ToastTintUtils.normal(ResourceUtils.getString(R.string.str_export_ing))
                    // 导出应用
                    var result = FileUtils.copyFile(
                        appInfoBean.sourceDir,
                        destFile.absolutePath,
                        true
                    )
                    if (result) {
                        ToastTintUtils.success(
                            ResourceUtils.getString(R.string.str_export_suc)
                                    + " " + PathConfig.AEP_APK_PATH + fileName
                        )
                    } else {
                        ToastTintUtils.error(ResourceUtils.getString(R.string.str_export_fail))
                    }
                }
            }
            R.id.bmpi_export_apk_msg -> {
                HandlerUtils.postRunnable {
                    val appInfoBean = apkInfoItem!!.appInfoBean
                    // 应用名_包名_版本名.txt
                    var fileName =
                        appInfoBean.appName + "_" + appInfoBean.appPackName + "_" + appInfoBean.versionName + ".txt"
                    // 导出数据
                    var result = FileUtils.saveFile(
                        FileUtils.getFile(PathConfig.AEP_APKMSG_PATH, fileName),
                        ProjectUtils.toJson(apkInfoItem)?.toByteArray()
                    )
                    if (result) {
                        ToastTintUtils.success(
                            ResourceUtils.getString(R.string.str_export_suc)
                                    + " " + PathConfig.AEP_APKMSG_PATH + fileName
                        )
                    } else {
                        ToastTintUtils.error(ResourceUtils.getString(R.string.str_export_fail))
                    }
                }
            }
        }
        return true
    }
}