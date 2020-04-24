import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.tools.*
import java.io.File
import java.nio.file.Files
import java.util.*

data class Presets(
  
  val include: List<String>,
  val preload: List<String>,
  val link: List<String>,
  val target: String,
  val mapper: (InfoMap) -> Unit
)

class MParser(logger: Logger = Logger.create(MParser::class.java), properties: Properties) :
  Parser(logger, properties) {
  
  fun parse(outputDirectory: File?, preset: Presets): Array<File> {
    val allProperties = MClassPropertiesLoader.load(properties, preset)
    val clsProperties = MClassPropertiesLoader.load(properties, preset)
    
    val cIncludes: MutableList<String> = ArrayList()
    cIncludes.addAll(clsProperties["platform.cinclude"])
    cIncludes.addAll(allProperties["platform.cinclude"])
    
    val clsIncludes: MutableList<String> = ArrayList()
    clsIncludes.addAll(clsProperties["platform.include"])
    clsIncludes.addAll(clsProperties["platform.cinclude"])
    
    val allIncludes: MutableList<String> = ArrayList()
    allIncludes.addAll(allProperties["platform.include"])
    allIncludes.addAll(allProperties["platform.cinclude"])
    val allTargets = allProperties["target"]
    val allGlobals = allProperties["global"]
    val clsTargets = clsProperties["target"]
    val clsGlobals = clsProperties["global"]
    // There can only be one target, pick the last one set
    val target = clsTargets[clsTargets.size - 1]
    val global = clsGlobals[clsGlobals.size - 1]
    
    infoMap = InfoMap()
    leafInfoMap = InfoMap()
    preset.mapper(leafInfoMap)
    infoMap.putAll(leafInfoMap)
    
    val version = Parser::class.java.getPackage().implementationVersion ?: "unknown"
    val n = global.lastIndexOf('.')
    var text = ""
    val header = "// Targeted by JavaCPP version $version: DO NOT EDIT THIS FILE\n\n"
    val targetHeader = """
      ${header}package $target;
      
      
      """.trimIndent()
    val globalHeader = header + if (n >= 0) """
   package ${global.substring(0, n)};
   
   
   """.trimIndent() else ""
    
    text += """
      import java.nio.*;
      import org.bytedeco.javacpp.*;
      import org.bytedeco.javacpp.annotation.*;
      
      
      """.trimIndent()
    
    val globalText =
      """$globalHeader${text}
@Properties(
        value = @Platform(
                include = {${preset.include.joinToString { "\"$it\"" }}},
                com.github.wumo.javacpp.getPreload = {${preset.preload.joinToString { "\"$it\"" }}},
                link = {${preset.link.joinToString { "\"$it\"" }}}
        ),
        target = "${preset.target}"
)
public class ${global.substring(n + 1)}  {
    static { Loader.load(); }
"""
    
    val targetPath = target.replace('.', File.separatorChar)
    val globalPath = global.replace('.', File.separatorChar)
    val targetFile = File(outputDirectory, targetPath)
    val globalFile = File(outputDirectory, "$globalPath.java")
    logger.info("Targeting $globalFile")
    val context = Context()
    context.infoMap = infoMap
    context.objectify = false
    val paths = allProperties["platform.includepath"]
    for (s in allProperties["platform.includeresource"])
      for (f in Loader.cacheResources(s))
        paths.add(f.canonicalPath)
    
    if (clsIncludes.size == 0) {
      logger.info("Nothing targeted for $globalFile")
      return emptyArray()
    }
    
    val includePaths = paths.toTypedArray()
    var declList = DeclarationList()
    declList = DeclarationList(declList)
    if (clsIncludes.size > 0) {
      containers(context, declList)
      for (include in allIncludes) {
        val isCFile = cIncludes.contains(include)
        parse(context, declList, includePaths, include, isCFile)
      }
    }
    
    val globalDir = globalFile.parentFile
    if (target != global) {
      targetFile.mkdirs()
    }
    globalDir?.mkdirs()
    val outputFiles = listOf(globalFile)
    (encoding?.let { EncodingFileWriter(globalFile, it, lineSeparator) }
      ?: EncodingFileWriter(globalFile, lineSeparator)).use { out ->
      out.append(globalText)
      var prevd: Declaration? = null
      for (d in declList) {
        if (prevd != null) out.append(prevd.text)
        prevd = d
      }
      if (prevd != null) {
        out.append(prevd.text)
      }
      out.append("\n}\n").close()
    }
    
    return outputFiles.toTypedArray()
  }
}