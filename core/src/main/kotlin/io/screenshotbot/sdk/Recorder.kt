package io.screenshotbot.sdk

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.jackson.jacksonDeserializerOf
import com.google.common.hash.Hashing
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import java.io.File
import java.lang.RuntimeException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.io.ByteSource
import org.apache.commons.cli.HelpFormatter
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.xml.stream.XMLInputFactory

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    visible = true,
    property = "type"
)
@JsonSubTypes(
    Type(value = Image::class, name = "image"),
    Type(value = CreateRunResponse::class, name="run")
)
abstract class ApiResponse (val type: String)

data class Image (@JsonProperty var id: String = "",
                  var uploadUrl:String? = null) : ApiResponse("image")

data class CreateRunResponse(@JsonProperty var id: Int = 0) : ApiResponse("run")

data class Result<T : ApiResponse>(var success: Boolean = false, var response: T? = null, var error: String? = null)
data class ScreenshotRecord(val name: String, val imageId: String)

data class Credential(var apiKey: String = "",
                      var apiSecretKey: String = "")

fun readConfig() = run {
    val mapper = ObjectMapper()
    mapper.registerModule(KotlinModule())

    val homeStr = System.getProperty("user.home")
    if (homeStr.isNullOrEmpty()) {
        throw RuntimeException("user.home is not set")
    }
    val home = File(homeStr)
    val file = File(home, ".screenshotbot")
    if (!file.exists()) {
        throw RuntimeException("Could not find config file at " + file)
    }
    val json = file.readText()
    //throw RuntimeException("got json: " + json)
    mapper.readValue<Credential>(json)
}

data class Screenshot(
        var description: String? = "",
        var name: String? = "",
        var test_class: String?="",
        var test_name: String? = "",
        var view_hierarchy: String? ="",
        var tile_width: Int? = null,
        var tile_height: Int? = null) {
}
class Screenshots() {

}

interface ImageProvider {
    fun getImage(name: String): BufferedImage
    fun close()
}

class DirectoryImageProvider(val dir: File) : ImageProvider {
    override fun getImage(name: String): BufferedImage = run {
        ImageIO.read(File(dir, name))
    }

    override fun close() {
    }
}

class ZipImageProvider(val file: File) : ImageProvider {
    val zipFile = ZipFile(file)
    override fun getImage(name: String): BufferedImage = run {
        val entry = zipFile.getEntry(name)
        ImageIO.read(zipFile.getInputStream(entry))
    }

    override fun close() {
        zipFile.close()
    }

}


data class GitStatus(val commit:String, val clean: Boolean)

class RepoProcessor {
    companion object {

        public fun getCommit(projectDir: File): GitStatus? {
            val gitDir = getGitDir(projectDir)
            if (gitDir == null) {
                return null
            }
            val git = Git.open(gitDir)
            val commit = git.repository.resolve(Constants.HEAD)

            val clean = git.status().call().isClean();
            return GitStatus(commit.name, clean)
        }

        private fun getGitDir(projectDir: File): File? {
            val gitDir = File(projectDir, ".git")
            if (gitDir.exists()) {
                return gitDir
            }

            val parent = projectDir.parentFile
            if (parent.equals(gitDir)) {
                return null
            }
            return getGitDir(parent)
        }
    }
}

class Recorder() {
    var branch: String? = null
    var clean: Boolean? = true
    var commit: String? = null
    var production: Boolean = false
    var iOSSnapshotTestCase: Boolean = false

    private var githubRepo: String? = ""
    var mapper = ObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)
    private var apiKey: String? = null
    private var apiSecret: String? = null

    fun getAllImages(dir: String) = run {
        var dirFile = File(dir)
        dirFile.listFiles().toList()
                .filter { x -> x.absolutePath.endsWith(".png") }
    }

    @Suppress("deprecation")
    fun getDigest(data: ByteArray) = run {
        ByteSource.wrap(data).hash(Hashing.md5()).toString()
    }

    public fun printHelp(options: Options) {
        val helpFormatter = HelpFormatter()
        helpFormatter.printHelp("java -jar recorder.jar [options]",
            "Process and record screenshots/snapshots to Screenshotbot.io",
            options,
            "Feel free to contact support@screenshotbot.io for any questions")
    }

    private fun run(args: Array<String>) {
        mapper.registerModule(KotlinModule())

        val options = Options()
        options.addOption("d", "dir", true, "Directory with screenshots, can also be a bundle.zip")
        options.addOption("c", "channel", true, "Channel name under which the screenshots should go under")
        options.addOption("m", "metadata", true, "Metadata file, defaults to dir/metadata.xml")
        options.addOption("p", "is-production", false, "Is `production`. For instance, CI runs. Only CI runs on master or release branches will be `promoted`. All other runs can still be viewed on screenshotbot.io")
        options.addOption("b", "branch", true, "Branch")
        options.addOption("r", "repo", true, "Github repository")

        options.addOption(null, "api-key", true, "Screenshotbot API key, otherwise we read from ~/.screenshotbot")
        options.addOption(null, "api-secret", true, "Screenshotot API secret, otherwise we read from ~/.screenshotbot")
        options.addOption("h", "help", false, "Display this help message")
        options.addOption(null, "ios-snapshot-test-case", false, "Assume the directory structure as generated by iOSSnapshotTestCase (ClassName/testName.png)")

        val parser = DefaultParser()
        val cli = parser.parse(options, args)

        if (cli.hasOption('h')) {
            printHelp(options)
            return
        }

        val dir = cli.getOptionValue('d')
        val channel = cli.getOptionValue('c')
        val metadata = File(cli.getOptionValue('m')) ?: File(dir, "metadata.xml")
        val status = RepoProcessor.getCommit(File("."))
        this.commit = status?.commit
        this.clean = status?.clean
        this.production = cli.hasOption('p')
        this.branch = cli.getOptionValue("branch")
        this.apiKey = cli.getOptionValue("api-key") ?: readConfig().apiKey
        this.apiSecret = cli.getOptionValue("api-secret") ?: readConfig().apiSecretKey
        this.githubRepo = cli.getOptionValue("repo")
        this.iOSSnapshotTestCase = cli.hasOption("ios-snapshot-test-case")
        doRecorder(channel, dir, metadata)

    }

    fun readMetadata(file: File) = run {
        val mapper = XmlMapper()
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val f = XMLInputFactory.newFactory()
        val sr = f.createXMLStreamReader(file.inputStream())

        sr.next() // <screenshots>
        sr.next() // <screenshot>

        val screenshots = mutableListOf<Screenshot>()
        logger.debug("Current element has name: " + sr.name)

        while (sr.isStartElement) {

            if (sr.name.localPart.equals("screenshot")) {
                screenshots.add(mapper.readValue<Screenshot>(sr, Screenshot::class.java))
            }

            sr.next() // <screenshot> or </screenshots>
        }

        screenshots.toList()
    }

    public fun doRecorder(channel: String, dir: String, metadata: File = File(dir, "metadata.xml")) {
        if (channel.isNullOrEmpty())
            throw RuntimeException("empty channel")

        if (dir.isNullOrEmpty())
            throw RuntimeException("no directory specified")

        val allImages = if (this.iOSSnapshotTestCase) {
            uploadIOSImages(dir)
        } else uploadAndroidImages(metadata, dir)

        makeRun(channel, allImages)
    }

    private fun uploadIOSImages(dir: String, prefix: String ="") :List<ScreenshotRecord> = run {
        val dirFile = File(dir)
        dirFile.listFiles().flatMap {
            if (it.isDirectory) {
                uploadIOSImages(it.absolutePath, prefix + "/" + it.name)
            } else {
                val data = it.readBytes()
                val response = uploadImage(it.absolutePath, data)
                listOf(ScreenshotRecord(prefix + "/" + it.nameWithoutExtension, response.id!!))
            }
        }
    }

    private fun uploadAndroidImages(
        metadata: File,
        dir: String
    ): List<ScreenshotRecord> {
        val screenshots = readMetadata(metadata)

        val imageProvider = if (File(dir).isDirectory) {
            DirectoryImageProvider(File(dir))
        } else ZipImageProvider(File(dir))

        logger.info("Got ${screenshots.size} screenshots to upload")
        val allImages = screenshots.map { screenshot ->
            // todo: get all the other tiles
            val imgs = Array<Array<BufferedImage>>(screenshot.tile_height!!) { h ->
                Array<BufferedImage>(screenshot.tile_width!!) { w ->
                    val file = (if (h == 0 && w == 0) (screenshot.name + ".png")
                    else "${screenshot.name}_${w}_${h}.png")

                    imageProvider.getImage(file)
                }
            }
            val width = imgs[0].map {
                it.width
            }.sum()
            val height = imgs.map {
                it[0].height
            }.sum()

            val outputImg = BufferedImage(width, height, imgs[0][0].type)

            var h = 0;
            imgs.map {
                var w = 0;
                it.map {
                    outputImg.raster.setRect(w, h, it.raster)
                    w += it.width
                }
                h += it[0].height
            }

            val data = ByteArrayOutputStream()
            ImageIO.write(outputImg, "png", data)
            val response = uploadImage(screenshot.name + ".png", data.toByteArray())
            ScreenshotRecord(screenshot.name!!, response.id!!)
        }
        return allImages
    }

    fun getApiKey() = run {
        this.apiKey ?: readConfig().apiKey
    }

    fun getApiSecret() = run {
        this.apiSecret ?: readConfig().apiSecretKey
    }

    private fun makeRun(channel: String, allImages: List<ScreenshotRecord>) = run {
        logger.info("Finalizing the reporter run")
        val recordsJson = mapper.writeValueAsString(allImages)
        val resp = Fuel.post(buildUrl("/api/run"),
                  listOf("channel" to channel, "screenshot-records" to recordsJson,
                         "github-repo" to githubRepo,
                         "commit" to commit,
                         "is-clean" to clean.toString(),
                         "branch" to branch,
                         "is-trunk" to production.toString(),
                         "api-key" to getApiKey(),
                         "api-secret-key" to getApiSecret()))
            .responseObject<Result<CreateRunResponse>>(jacksonDeserializerOf(mapper)).second;

        if (resp.statusCode != 200) {
            throw RuntimeException("Failed to finalize run, got code ${resp.statusCode}, contact support@sylkworm.io for help")
        }
    }

    fun buildUrl(url: String) = run {
        "https://api.screenshotbot.io" + url
    }

    private fun uploadImage(fileName: String, data: ByteArray) = run {
        logger.info("Uploading file: " + fileName)
        val hash = getDigest(data)
        val result: Result<Image> =
            Fuel.post(buildUrl("/api/screenshot"),
                      listOf("name" to fileName, "hash" to hash,
                         "api-key" to getApiKey(),
                         "api-secret-key" to getApiSecret()))
                .responseObject<Result<Image>>(jacksonDeserializerOf(mapper)).third.get()

        val response = result.response!!

        if (!response.uploadUrl.isNullOrEmpty()) {
            // let's start the upload process

            logger.info("New image, never seen before. Uploading!")
            val code = Fuel.put(response.uploadUrl!!)
                        .body(data)
                        .response().second

            if (code.statusCode != 200) {
                throw RuntimeException("Error while uploading image data")
            }
        } else {
            logger.debug("reusing existing image")
        }
        response
    }

    fun setGithubRepo(githubRepo: String?) {
        this.githubRepo = githubRepo
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Recorder().run(args)
        }


    }
}
