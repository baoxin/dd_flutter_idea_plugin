package shop.itbug.fluttercheckversionx.notif

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.yaml.psi.YAMLFile
import shop.itbug.fluttercheckversionx.common.yaml.PubspecYamlFileTools
import shop.itbug.fluttercheckversionx.dialog.SearchDialog
import shop.itbug.fluttercheckversionx.i18n.PluginBundle
import shop.itbug.fluttercheckversionx.icons.MyIcons
import shop.itbug.fluttercheckversionx.services.PubCacheSizeCalcService
import shop.itbug.fluttercheckversionx.services.PubCacheSizeCalcService.Companion.TOPIC
import shop.itbug.fluttercheckversionx.services.noused.DartNoUsedCheckService
import shop.itbug.fluttercheckversionx.setting.IgPluginPubspecConfigList
import shop.itbug.fluttercheckversionx.tools.MyToolWindowTools
import shop.itbug.fluttercheckversionx.tools.log
import shop.itbug.fluttercheckversionx.util.MyFileUtil
import shop.itbug.fluttercheckversionx.util.runWriteCommandAction
import shop.itbug.fluttercheckversionx.util.toast
import shop.itbug.fluttercheckversionx.util.toastWithError
import java.awt.event.InputEvent
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

class PubPluginVersionCheckNotification : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project, file: VirtualFile
    ): Function<in FileEditor, out JComponent?> {
        return Function<FileEditor, JComponent?> {
            if (project.isDisposed) return@Function null
            if (it.component.parent == null) return@Function null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@Function null
            if (file.name == "pubspec.yaml" && psiFile is YAMLFile) {
                log().warn("start check is flutter project")
                val isFlutterProject =
                    runBlocking(Dispatchers.IO) {
                        PubspecYamlFileTools.create(psiFile).isFlutterProject()
                    }
                log().warn("is a flutter project: $isFlutterProject")
                if (!isFlutterProject) return@Function null
                val panel = YamlFileNotificationPanel(it, psiFile, project)
                return@Function panel
            }
            return@Function null
        }
    }

}


private class YamlFileNotificationPanel(
    fileEditor: FileEditor,
    val file: YAMLFile,
    val project: Project
) :
    EditorNotificationPanel(fileEditor, UIUtil.getEditorPaneBackground()) {

    private val pubCacheSizeComponent = MyCheckPubCacheSizeComponent(project)

    init {
        Disposer.register(PubCacheSizeCalcService.getInstance(project), pubCacheSizeComponent)

        myLinksPanel.add(pubCacheSizeComponent)

        icon(MyIcons.dartPluginIcon)
        text(PluginBundle.get("w.t"))

        val searchPluginLabel = createActionLabel(PluginBundle.get("search.pub.plugin")) {
            search()
        }
        myLinksPanel.add(searchPluginLabel)


        ///重新索引
        val reIndexLabel = createActionLabel(PluginBundle.get("pubspec_yaml_file_re_index")) {
            MyFileUtil.reIndexWithVirtualFile(file.virtualFile)
            DaemonCodeAnalyzer.getInstance(project).restart(file)
        }

        myLinksPanel.add(reIndexLabel)


        ///打开隐私扫描窗口
        val openPrivacyWindowLabel =
            createActionLabel(PluginBundle.get("are_you_ok_betch_insert_privacy_file_window_title")) {
                doOpenPrivacyWindow()
            }

        myLinksPanel.add(openPrivacyWindowLabel)


        ///管理忽略的包
        val igPackageLabel = createActionLabel("Ignore packages") {
            IgPluginPubspecConfigList.showInPopup(project, file)
        }
        myLinksPanel.add(igPackageLabel)

        ///检查没有被使用的包
        val noUsedCheck = createActionLabel(PluginBundle.get("check_un_used_package")) {
            DartNoUsedCheckService.getInstance(project).checkUnUsedPackaged()
        }
        myLinksPanel.add(noUsedCheck)

        ///生成 Assets 配置
        val generateAssetsLabel = createActionLabel(PluginBundle.get("assets.yaml.gen")) {
            generateAssetsConfig()
        }
        myLinksPanel.add(generateAssetsLabel)
    }

    ///打开隐私扫描工具窗口
    private fun doOpenPrivacyWindow() {
        val myToolWindow = MyToolWindowTools.getMyToolWindow(project)
        myToolWindow?.let {
            it.activate {
                val content = it.contentManager.getContent(4)
                if (content != null) {
                    it.contentManager.setSelectedContent(content)
                }
            }
        }
    }

    private fun search() {
        SearchDialog(project).show()
    }

    /**
     * 生成 Assets 配置
     */
    private fun generateAssetsConfig() {
        try {
            val assetsDir = findAssetsDirectory()
            if (assetsDir == null) {
                project.toast("未找到 assets 目录")
                return
            }

            // 扫描 assets 目录，收集所有路径
            val assetsPaths = collectAssetsPaths(assetsDir)

            // 更新 pubspec.yaml 文件
            updatePubspecYaml(assetsPaths)

            project.toast("Assets 配置生成成功！")
        } catch (ex: Exception) {
            project.toastWithError("生成失败：${ex.message}")
        }
    }

    /**
     * 查找 assets 目录
     */
    private fun findAssetsDirectory(): VirtualFile? {
        val pubspecDir = file.virtualFile.parent ?: return null
        return pubspecDir.findChild("assets")
    }

    /**
     * 收集 assets 目录下的所有路径
     */
    private fun collectAssetsPaths(assetsDir: VirtualFile): List<String> {
        val paths = mutableSetOf<String>()

        // 添加根目录
        paths.add("assets/")

        // 递归收集所有子目录
        collectDirectoryPaths(assetsDir, "assets", paths)

        return paths.sorted()
    }

    /**
     * 递归收集目录路径
     */
    private fun collectDirectoryPaths(
        directory: VirtualFile,
        currentPath: String,
        paths: MutableSet<String>
    ) {
        directory.children.forEach { child ->
            if (child.isDirectory) {
                val childPath = "$currentPath/${child.name}/"
                paths.add(childPath)
                // 递归处理子目录
                collectDirectoryPaths(child, "$currentPath/${child.name}", paths)
            }
        }
    }

    /**
     * 更新 pubspec.yaml 文件的 assets 配置
     */
    private fun updatePubspecYaml(assetsPaths: List<String>) {
        file.runWriteCommandAction {
            try {
                updateYamlFileContent(assetsPaths)
            } catch (e: Exception) {
                throw RuntimeException("更新 pubspec.yaml 失败：${e.message}", e)
            }
        }
    }

    /**
     * 更新 YAML 文件内容
     */
    private fun updateYamlFileContent(assetsPaths: List<String>) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
            ?: throw IllegalStateException("无法获取文档")

        val content = document.text
        val lines = content.lines().toMutableList()

        // 查找顶层的 flutter: 部分（不以空格或制表符开头）
        val flutterIndex = lines.indexOfFirst {
            val trimmed = it.trim()
            trimmed.startsWith("flutter:") && !it.startsWith(" ") && !it.startsWith("\t")
        }
        if (flutterIndex == -1) {
            throw IllegalStateException("未找到顶层 flutter: 配置部分")
        }

        // 查找或创建 assets: 部分
        val assetsIndex = findOrCreateAssetsSection(lines, flutterIndex)

        // 替换 assets 配置
        replaceAssetsConfig(lines, assetsIndex, assetsPaths)

        // 更新文档内容
        document.setText(lines.joinToString("\n"))
    }

    /**
     * 查找或创建 assets 配置部分
     */
    private fun findOrCreateAssetsSection(lines: MutableList<String>, flutterIndex: Int): Int {
        // 在 flutter: 部分查找 assets:
        var i = flutterIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 如果遇到同级别的其他配置项，说明没有 assets 配置
            if (trimmed.isNotEmpty() && !line.startsWith("  ") && !line.startsWith("\t")) {
                break
            }

            // 找到 assets 配置
            if (trimmed.startsWith("assets:")) {
                return i
            }

            i++
        }

        // 没有找到 assets 配置，需要创建
        val insertIndex = findInsertPosition(lines, flutterIndex)
        lines.add(insertIndex, "  assets:")
        return insertIndex
    }

    /**
     * 查找插入 assets 配置的位置
     * @param lines 文件行列表
     * @param flutterIndex flutter: 行的索引
     * @return 插入位置的索引
     */
    private fun findInsertPosition(lines: MutableList<String>, flutterIndex: Int): Int {
        var i = flutterIndex + 1
        var insertPosition = flutterIndex + 1 // 默认插入位置为 flutter: 后面
        var foundFirstConfig = false

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 如果遇到同级别的其他配置项（不以空格或制表符开头），说明 flutter 区域结束
            if (trimmed.isNotEmpty() && !line.startsWith("  ") && !line.startsWith("\t")) {
                break
            }

            // 如果是 flutter 区域内的顶级配置项（以两个空格开头，包含冒号，不是列表项）
            if ((line.startsWith("  ") || line.startsWith("\t")) &&
                trimmed.contains(":") &&
                !trimmed.startsWith("-") &&
                trimmed.isNotEmpty()) {

                if (!foundFirstConfig) {
                    // 找到第一个配置项，记录其位置
                    foundFirstConfig = true
                    insertPosition = i + 1

                    // 跳过该配置项的所有子项和空行
                    var j = i + 1
                    while (j < lines.size) {
                        val nextLine = lines[j]
                        val nextTrimmed = nextLine.trim()

                        // 如果是空行，跳过
                        if (nextTrimmed.isEmpty()) {
                            j++
                            continue
                        }

                        // 如果是同级别配置项或更高级别，停止
                        if (!nextLine.startsWith("    ") && !nextLine.startsWith("\t\t")) {
                            break
                        }

                        j++
                    }
                    insertPosition = j
                    break
                }
            }

            i++
        }

        return insertPosition
    }

    /**
     * 替换 assets 配置内容
     */
    private fun replaceAssetsConfig(
        lines: MutableList<String>,
        assetsIndex: Int,
        assetsPaths: List<String>
    ) {
        // 删除现有的 assets 配置项
        var i = assetsIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            // 如果是 assets 的子项（以更多空格开头）
            if (line.startsWith("    ") || line.startsWith("\t\t") ||
                (line.trim()
                    .startsWith("- ") && (line.startsWith("    ") || line.startsWith("\t\t")))
            ) {
                lines.removeAt(i)
            } else {
                break
            }
        }

        // 添加新的 assets 配置项
        assetsPaths.forEachIndexed { index, path ->
            lines.add(assetsIndex + 1 + index, "    - $path")
        }
    }

}


///计算pub cache 占用大小
private class MyCheckPubCacheSizeComponent(project: Project) : HyperlinkLabel(),
    PubCacheSizeCalcService.Listener,
    Disposable, BulkFileListener {
    val cacheService = PubCacheSizeCalcService.getInstance(project)
    private var isDisposed = false

    init {
        project.messageBus.connect(cacheService).subscribe(TOPIC, this)
        project.messageBus.connect(parentDisposable = this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            this
        )
        Disposer.register(cacheService, this)
        SwingUtilities.invokeLater {
            setDefaultText()
        }
        ToolTipManager.sharedInstance().registerComponent(this)
        toolTipText = cacheService.getPubCacheDirPathString()
        ApplicationManager.getApplication().invokeLater {
            cacheService.refreshCheck()
        }
    }

    override fun fireHyperlinkEvent(inputEvent: InputEvent?) {
        cacheService.openDir()
        super.fireHyperlinkEvent(inputEvent)
    }


    private fun setDefaultText() {
        setHyperlinkText("Pub Cache Size: " + cacheService.getCurrentSizeFormatString())
    }

    override fun calcComplete(len: Long, formatString: String) {
        SwingUtilities.invokeLater {
            if (!isDisposed) {
                setHyperlinkText("Pub Cache Size: $formatString")
            }

        }

    }

    override fun dispose() {
        println("dispose pub cache size widget")
        isDisposed = true
        ToolTipManager.sharedInstance().unregisterComponent(this)
    }

    override fun after(events: List<VFileEvent>) {
        val cachePath = cacheService.getCachePath()
        if (events.isNotEmpty() && cachePath.isNullOrBlank()
                .not() && events.any { it.file?.path?.startsWith(cachePath) == true }
        ) {
            cacheService.refreshCheck()
        }
        super.after(events)
    }
}

