import com.github.wumo.javacpp.MParser
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.tools.*
import java.io.File
import java.nio.file.Files
import java.util.*

class NParser(logger: Logger = Logger.create(MParser::class.java), properties: Properties) :
  Parser(logger, properties) {
  
  fun parse(outputDirectory: File?, cls: Class<*>): Array<File> {
    val allProperties = Loader.loadProperties(cls, properties, true)
    val clsProperties = Loader.loadProperties(cls, properties, false)
    
    // Capture c-includes from "class" and "all" properties
    
    // Capture c-includes from "class" and "all" properties
    val cIncludes: MutableList<String> = ArrayList()
    cIncludes.addAll(clsProperties["platform.cinclude"])
    cIncludes.addAll(allProperties["platform.cinclude"])
    
    // Capture class includes
    
    // Capture class includes
    val clsIncludes: MutableList<String> = ArrayList()
    clsIncludes.addAll(clsProperties["platform.include"])
    clsIncludes.addAll(clsProperties["platform.cinclude"])
    
    // Capture all includes
    
    // Capture all includes
    val allIncludes: MutableList<String> = ArrayList()
    allIncludes.addAll(allProperties["platform.include"])
    allIncludes.addAll(allProperties["platform.cinclude"])
    val allTargets = allProperties["target"]
    val allGlobals = allProperties["global"]
    val clsTargets = clsProperties["target"]
    val clsGlobals = clsProperties["global"]
    val clsHelpers = clsProperties["helper"]
    // There can only be one target, pick the last one set
    // There can only be one target, pick the last one set
    val target = clsTargets[clsTargets.size - 1]
    val global = clsGlobals[clsGlobals.size - 1]
    val allInherited = allProperties.inheritedClasses
    
    infoMap = InfoMap()
    leafInfoMap = InfoMap()
    infoMap.putAll(leafInfoMap)
    
    val version = Parser::class.java.getPackage().implementationVersion ?: "unknown"
    val n = global.lastIndexOf('.')
    var text = ""
    val header = "// Targeted by JavaCPP version $version: DO NOT EDIT THIS FILE\n\n"
    val targetHeader = """
      ${header}package $target;
      
      
      """.trimIndent()
    var globalHeader = header + if (n >= 0) """
   package ${global.substring(0, n)};
   
   
   """.trimIndent() else ""
    val infoList = leafInfoMap[null]
    var objectify = false
    for (info in infoList) {
      objectify = objectify or (info != null && info.objectify)
      if (info!!.javaText != null && info.javaText.startsWith("import")) {
        text += """
          ${info.javaText}
          
          """.trimIndent()
      }
    }
    if (target != global && targetHeader != globalHeader) {
      globalHeader += "import $target.*;\n\n"
    }
    text += """
      import java.nio.*;
      import org.bytedeco.javacpp.*;
      import org.bytedeco.javacpp.annotation.*;
      
      
      """.trimIndent()
    for (i in allTargets.indices) {
      if (target != allTargets[i]) {
        text += if (allTargets[i] == allGlobals[i]) {
          """
   import static ${allTargets[i]}.*;
   
   """.trimIndent()
        } else {
          """
   import ${allTargets[i]}.*;
   import static ${allGlobals[i]}.*;
   
   """.trimIndent()
        }
      }
    }
    if (allTargets.size > 1) {
      text += "\n"
    }
    val globalText =
      """$globalHeader${text}public class ${global.substring(n + 1)}  {
    static { Loader.load(); }
"""
    
    val targetPath = target.replace('.', File.separatorChar)
    val globalPath = global.replace('.', File.separatorChar)
    val targetFile = File(outputDirectory, targetPath)
    val globalFile = File(outputDirectory, "$globalPath.java")
    logger.info("Targeting $globalFile")
    val context = Context()
    context.infoMap = infoMap
    context.objectify = objectify
    val paths = allProperties["platform.includepath"]
    for (s in allProperties["platform.includeresource"]) {
      for (f in Loader.cacheResources(s)) {
        paths.add(f.canonicalPath)
      }
    }
    
    if (clsIncludes.size == 0) {
      logger.info("Nothing targeted for $globalFile")
      return emptyArray()
    }
    
    val includePaths = paths.toTypedArray()
    var declList = DeclarationList()
    for (include in allIncludes) {
      if (!clsIncludes.contains(include)) {
        val isCFile = cIncludes.contains(include)
        parse(context, declList, includePaths, include, isCFile)
      }
    }
    declList = DeclarationList(declList)
    if (clsIncludes.size > 0) {
      containers(context, declList)
      for (include in clsIncludes) {
        if (allIncludes.contains(include)) {
          val isCFile = cIncludes.contains(include)
          parse(context, declList, includePaths, include, isCFile)
        }
      }
    }
    
    if (declList.size == 0) {
      logger.info("Nothing targeted for $globalFile")
      return emptyArray()
    }
    
    val globalDir = globalFile.parentFile
    if (target != global) {
      targetFile.mkdirs()
    }
    globalDir?.mkdirs()
    val outputFiles =
      ArrayList(Arrays.asList(globalFile))
    encoding?.let { EncodingFileWriter(globalFile, it, lineSeparator) }
      ?: EncodingFileWriter(
        globalFile,
        lineSeparator
      ).use { out ->
        out.append(globalText)
        for (info in infoList) {
          if (info.javaText != null && !info.javaText.startsWith("import")) {
            out.append(
              """
                ${info.javaText}
                
                """.trimIndent()
            )
          }
        }
        var prevd: Declaration? = null
        for (d in declList) {
          prevd = if (target != global && d.type != null && d.type.javaName != null && d.type.javaName.length > 0) {
            // when "target" != "global", the former is a package where to output top-level classes into their own files
            val shortName = d.type.javaName.substring(d.type.javaName.lastIndexOf('.') + 1)
            val javaFile = File(targetFile, "$shortName.java")
            if (prevd != null && !prevd.comment) {
              out.append(prevd.text)
            }
            out.append(
              """
                
                // Targeting ${globalDir!!.toPath().relativize(javaFile.toPath())}
                
                
                """.trimIndent()
            )
            logger.info("Targeting $javaFile")
            val javaText = ("""
  $targetHeader${text}import static $global.*;
  ${if (prevd != null && prevd.comment) prevd.text else ""}
  """.trimIndent()
                + d.text.replace(
              "public static class $shortName ",
              """@Properties(inherit = ${cls!!.canonicalName}.class)
public class $shortName """
            ) + "\n")
            outputFiles.add(javaFile)
            Files.write(
              javaFile.toPath(),
              if (encoding != null) javaText.toByteArray(charset(encoding)) else javaText.toByteArray()
            )
            null
          } else {
            if (prevd != null) {
              out.append(prevd.text)
            }
            d
          }
        }
        if (prevd != null) {
          out.append(prevd.text)
        }
        out.append("\n}\n").close()
      }
    
    return outputFiles.toTypedArray()
  }
}