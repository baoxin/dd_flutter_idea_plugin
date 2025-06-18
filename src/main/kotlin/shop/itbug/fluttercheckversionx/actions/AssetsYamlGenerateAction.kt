package shop.itbug.fluttercheckversionx.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import shop.itbug.fluttercheckversionx.common.MyAction
import shop.itbug.fluttercheckversionx.i18n.PluginBundle
import shop.itbug.fluttercheckversionx.util.MyFileUtil
import shop.itbug.fluttercheckversionx.util.toast

/**
 * 生成 pubspec.yaml 文件的 assets 配置
 * 扫描 Flutter 项目中的 assets 目录层级，生成对应的 assets 配置
 */
class AssetsYamlGenerateAction : MyAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)!!
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)!!
        
        try {
            generateAssetsConfig(project, vf)
            project.toast("Assets 配置生成成功！")
        } catch (ex: Exception) {
            project.toast("生成失败：${ex.message}")
        }
    }

    override fun update(e: AnActionEvent) {
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project

        // 只在 assets 目录上显示此菜单项，并且项目中需要有 pubspec.yaml 文件
        val isAssetsDirectory = vf != null && vf.isDirectory &&
                               vf.name == "assets" && project != null &&
                               MyFileUtil.getPubspecFile(project) != null

        e.presentation.isEnabledAndVisible = isAssetsDirectory
        e.presentation.text = PluginBundle.get("assets.yaml.gen")
        e.presentation.icon = AllIcons.FileTypes.Yaml
        super.update(e)
    }

    /**
     * 生成 assets 配置
     * @param project 项目实例
     * @param assetsDir assets 目录
     */
    private fun generateAssetsConfig(project: Project, assetsDir: VirtualFile) {
        // 获取 pubspec.yaml 文件
        val pubspecFile = MyFileUtil.getPubspecFile(project)
            ?: throw IllegalStateException("未找到 pubspec.yaml 文件")

        // 扫描 assets 目录，收集所有路径
        val assetsPaths = collectAssetsPaths(assetsDir)
        
        // 更新 pubspec.yaml 文件
        updatePubspecYaml(project, pubspecFile, assetsPaths)
    }

    /**
     * 收集 assets 目录下的所有路径
     * @param assetsDir assets 根目录
     * @return 排序后的路径列表
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
     * @param directory 当前目录
     * @param currentPath 当前路径
     * @param paths 路径集合
     */
    private fun collectDirectoryPaths(directory: VirtualFile, currentPath: String, paths: MutableSet<String>) {
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
     * @param project 项目实例
     * @param pubspecFile pubspec.yaml 文件
     * @param assetsPaths assets 路径列表
     */
    private fun updatePubspecYaml(project: Project, pubspecFile: YAMLFile, assetsPaths: List<String>) {
        WriteCommandAction.runWriteCommandAction(project, "Generate Assets Config", null, Runnable {
            try {
                // 这里需要实现 YAML 文件的修改逻辑
                // 由于 YAML 文件操作比较复杂，我们先用简单的文本替换方式
                updateYamlFileContent(project, pubspecFile, assetsPaths)
            } catch (e: Exception) {
                throw RuntimeException("更新 pubspec.yaml 失败：${e.message}", e)
            }
        })
    }

    /**
     * 更新 YAML 文件内容
     * @param project 项目实例
     * @param pubspecFile pubspec.yaml 文件
     * @param assetsPaths assets 路径列表
     */
    private fun updateYamlFileContent(project: Project, pubspecFile: YAMLFile, assetsPaths: List<String>) {
        val document = PsiDocumentManager.getInstance(project).getDocument(pubspecFile)
            ?: throw IllegalStateException("无法获取文档")

        val content = document.text
        val lines = content.lines().toMutableList()
        
        // 查找 flutter: 部分
        val flutterIndex = lines.indexOfFirst { it.trim().startsWith("flutter:") }
        if (flutterIndex == -1) {
            throw IllegalStateException("未找到 flutter: 配置部分")
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
     * @param lines 文件行列表
     * @param flutterIndex flutter: 行的索引
     * @return assets: 行的索引
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
     * @param lines 文件行列表
     * @param assetsIndex assets: 行的索引
     * @param assetsPaths assets 路径列表
     */
    private fun replaceAssetsConfig(lines: MutableList<String>, assetsIndex: Int, assetsPaths: List<String>) {
        // 删除现有的 assets 配置项
        var i = assetsIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            // 如果是 assets 的子项（以更多空格开头）
            if (line.startsWith("    ") || line.startsWith("\t\t") || 
                (line.trim().startsWith("- ") && (line.startsWith("    ") || line.startsWith("\t\t")))) {
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
