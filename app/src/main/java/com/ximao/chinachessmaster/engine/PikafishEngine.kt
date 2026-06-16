package com.ximao.chinachessmaster.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Pikafish UCI 象棋引擎封装
 *
 * Android SELinux 不允许 app 直接 exec 自己写入的文件（filesDir），
 * 但允许通过 `/system/bin/sh` 中转执行 nativeLibraryDir 的文件：
 *   ProcessBuilder("sh", "-c", enginePath) → 由 shell fork，引擎正常运行
 *
 * 引擎：libpikafish.so 打包进 jniLibs，系统安装到 nativeLibraryDir（有执行权限）
 * NNUE 模型：打包进 assets，首次运行时复制到 filesDir
 *
 * UCI 通信流程：
 *   uci → uciok → setoption EvalFile → isready → readyok
 *   position fen <FEN> → go movetime <ms> → bestmove <move>
 */
class PikafishEngine(private val context: Context) {

    companion object {
        private const val TAG = "PikafishEngine"
        private const val ASSET_DIR = "pikafish"
        private const val NNUE_FILE = "pikafish.nnue"
        private const val ENGINE_SO_NAME = "libpikafish.so"
        private const val INIT_TIMEOUT_MS = 15_000L
    }

    private var engineProcess: Process? = null
    private var uciWriter: PrintWriter? = null
    private var uciReader: BufferedReader? = null
    var isReady = false
        private set
    private var hasSearched = false  // 是否已经完成过至少一次搜索，用于决定是否发 stop
    private val searchMutex = Mutex()  // 保证同一时刻只有一个搜索请求
    private var engineFilePath: String = ""  // 缓存引擎路径，供崩溃重启使用

    // 引擎二进制：系统安装到 nativeLibraryDir，有执行权限
    private val nativeLibDir: File by lazy { File(context.applicationInfo.nativeLibraryDir) }

    // NNUE 模型：从 assets 复制到 filesDir
    private val nnueDir: File by lazy { File(context.filesDir, ASSET_DIR) }

    /**
     * 初始化引擎：
     *   1. 从 assets 复制 NNUE 模型到 filesDir（首次，51MB，后续跳过）
     *   2. 找到 nativeLibraryDir 中的 libpikafish.so
     *   3. 通过 sh 中转启动引擎子进程，完成 UCI 握手
     *
     * 为什么用 sh 中转：Java ProcessBuilder 直接 exec 静态链接 ELF 可执行文件时
     * 在 Android 上会 SIGSEGV（exit 139），由 shell fork 执行则正常。
     * @return true 表示引擎就绪
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            copyNnueIfNeeded()

            val engineFile = File(nativeLibDir, ENGINE_SO_NAME)
            if (!engineFile.exists()) {
                Log.e(TAG, "$ENGINE_SO_NAME not found in ${nativeLibDir.absolutePath}")
                return@withContext false
            }

            engineFilePath = engineFile.absolutePath
            val nnuePath = File(nnueDir, NNUE_FILE).absolutePath
            Log.d(TAG, "Starting engine via sh: $engineFilePath")
            Log.d(TAG, "NNUE path: $nnuePath")

            val started = startEngineProcess(engineFilePath, nnuePath)
            isReady = started
            Log.d(TAG, "Engine init result: $isReady")
            isReady
        } catch (e: Exception) {
            Log.e(TAG, "Engine init failed", e)
            false
        }
    }

    /**
     * 给定 FEN 局面，返回最佳走法（ICCS 格式，如 "h2e2"）
     *
     * 每次搜索前先发 "stop" 中断可能存在的 ponder 状态，
     * 再发 "position" 和 "go"，避免引擎处于非就绪状态时收到命令导致输出混乱。
     *
     * @param fen 标准 FEN 字符串
     * @param moveTimeMs 搜索时限（毫秒）
     * @return 最佳走法字符串，null 表示失败
     */
    suspend fun getBestMove(fen: String, moveTimeMs: Int = 3000): String? {
        if (!isReady) {
            Log.w(TAG, "Engine not ready")
            return null
        }
        return searchMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // 只有搜索过至少一次后才发 stop（第一次搜索前发 stop 会干扰引擎初始化状态）
                    if (hasSearched) {
                        sendCommand("stop")
                        drainOutput()
                    }

                    sendCommand("position fen $fen")
                    sendCommand("go movetime $moveTimeMs")

                    val bestMove = withTimeoutOrNull((moveTimeMs + 6000).toLong()) {
                        readUntilBestMoveWithRetry(fen, moveTimeMs)
                    }

                    if (bestMove != null) hasSearched = true
                    Log.d(TAG, "bestmove: $bestMove")
                    bestMove
                } catch (e: Exception) {
                    Log.e(TAG, "getBestMove failed", e)
                    null
                }
            }
        }
    }

    /**
     * 关闭引擎进程
     */
    fun close() {
        try {
            sendCommand("quit")
        } catch (_: Exception) {}
        uciWriter?.close()
        uciReader?.close()
        engineProcess?.destroy()
        engineProcess = null
        isReady = false
        Log.d(TAG, "Engine closed")
    }

    // ───────────────────────── 私有方法 ─────────────────────────

    /**
     * 启动引擎子进程并完成 UCI 握手，成功返回 true
     */
    private fun startEngineProcess(enginePath: String, nnuePath: String): Boolean {
        try {
            engineProcess?.destroy()
            engineProcess = null
            uciWriter?.close()
            uciReader?.close()

            // sh 中转 + ulimit -s unlimited 解除栈限制，避免 NNUE 64MB 分配时 Segfault
            engineProcess = ProcessBuilder("sh", "-c", "ulimit -s unlimited; $enginePath")
                .directory(nnueDir)
                .redirectErrorStream(true)
                .start()

            uciWriter = PrintWriter(engineProcess!!.outputStream, true)
            uciReader = BufferedReader(InputStreamReader(engineProcess!!.inputStream))
            hasSearched = false

            return performUciHandshake(nnuePath)
        } catch (e: Exception) {
            Log.e(TAG, "startEngineProcess failed", e)
            return false
        }
    }

    /**
     * 引擎崩溃后尝试重启，最多重试 2 次
     */
    private fun tryRestartEngine(): Boolean {
        val nnuePath = File(nnueDir, NNUE_FILE).absolutePath
        if (engineFilePath.isEmpty()) return false
        repeat(2) { attempt ->
            Log.w(TAG, "Restarting engine (attempt ${attempt + 1})...")
            if (startEngineProcess(engineFilePath, nnuePath)) {
                isReady = true
                Log.d(TAG, "Engine restarted successfully")
                return true
            }
        }
        Log.e(TAG, "Engine restart failed after 2 attempts")
        isReady = false
        return false
    }

    /**
     * 从 assets 复制 NNUE 模型到 filesDir（51MB，存在且大小正常则跳过）
     */
    private fun copyNnueIfNeeded() {
        nnueDir.mkdirs()
        val target = File(nnueDir, NNUE_FILE)
        if (target.exists() && target.length() > 1024 * 1024) {
            Log.d(TAG, "NNUE already exists (${target.length() / 1024 / 1024}MB), skipping")
            return
        }
        Log.d(TAG, "Copying NNUE model from assets...")
        context.assets.open("$ASSET_DIR/$NNUE_FILE").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = 512 * 1024)
            }
        }
        Log.d(TAG, "NNUE copy complete: ${target.length() / 1024 / 1024}MB")
    }

    /**
     * 根据设备实际情况计算最优线程数：
     * - 取物理核心数（大核）的一半，保留系统开销，最少 1 最多 4
     * - Android 上多线程 NNUE 每线程分配 64MB，线程过多易 Segfault 或 OOM
     */
    private fun optimalThreadCount(): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        // 取一半核心，避免抢占系统资源，上限 4（NNUE 每线程 64MB）
        return (cpuCount / 2).coerceIn(1, 4)
    }

    /**
     * 根据设备可用内存计算最优哈希表大小（MB）：
     * - 取可用内存的 10%，最少 16MB，最多 256MB
     * - 哈希表过大会触发 OOM，过小影响搜索深度
     */
    private fun optimalHashSizeMb(): Int {
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMb = memInfo.availMem / 1024 / 1024
        // 取可用内存的 10%
        return (availableMb / 10).toInt().coerceIn(16, 256)
    }

    /**
     * UCI 握手：uci → setoption → isready → readyok
     * 线程数和哈希表大小根据设备实际情况动态设置
     */
    private fun performUciHandshake(nnuePath: String): Boolean {
        sendCommand("uci")
        if (!readUntil("uciok")) {
            Log.e(TAG, "Did not receive uciok")
            return false
        }

        val threads = optimalThreadCount()
        val hashMb = optimalHashSizeMb()
        Log.d(TAG, "Device config → CPU cores: ${Runtime.getRuntime().availableProcessors()}, threads: $threads, hash: ${hashMb}MB")

        sendCommand("setoption name EvalFile value $nnuePath")
        sendCommand("setoption name NumaPolicy value none")  // 禁用 NUMA/共享内存，避免 Android SELinux 下 Segfault
        sendCommand("setoption name Threads value $threads")
        sendCommand("setoption name Hash value $hashMb")
        sendCommand("isready")

        if (!readUntil("readyok")) {
            Log.e(TAG, "Did not receive readyok")
            return false
        }

        Log.d(TAG, "UCI handshake complete")

        // 预热：发一次快速搜索，让引擎在初始化阶段完成 NNUE 推理工作区的内存分配。
        // 不预热时，第一次真正搜索（go movetime 3000）会触发 64MB mmap，概率 Segfault。
        Log.d(TAG, "Warming up engine...")
        sendCommand("position startpos")
        sendCommand("go movetime 100")
        readUntil("bestmove")
        Log.d(TAG, "Engine warmup complete")

        return true
    }

    private fun sendCommand(command: String) {
        Log.v(TAG, ">>> $command")
        uciWriter?.println(command)
    }

    /**
     * 持续读取引擎输出，直到某一行包含目标关键词
     */
    private fun readUntil(keyword: String): Boolean {
        val reader = uciReader ?: return false
        repeat(200) {
            val line = reader.readLine() ?: return false
            Log.v(TAG, "<<< $line")
            if (line.contains(keyword)) return true
        }
        return false
    }

    /**
     * 读取引擎输出，直到获取 "bestmove" 行。
     * readLine() 阻塞等待下一行，返回 null 仅当流关闭（引擎进程退出）时。
     * @return bestmove 后面的走法字符串（如 "h2e2"），"(none)" 视为 null
     */
    private fun readUntilBestMove(): String? {
        val reader = uciReader ?: return null
        while (true) {
            val line = reader.readLine() ?: run {
                val exitCode = try { engineProcess?.exitValue() } catch (_: IllegalThreadStateException) { null }
                Log.e(TAG, "Engine stdout closed. Process exit code: $exitCode")
                isReady = false
                return null
            }
            Log.v(TAG, "<<< $line")
            if (line.startsWith("bestmove")) {
                val move = line.split(" ").getOrNull(1)
                return if (move == null || move == "(none)") null else move
            }
        }
    }

    /**
     * 带自动重启的 bestmove 获取：引擎崩溃后重启并重试一次
     */
    private fun readUntilBestMoveWithRetry(fen: String, moveTimeMs: Int): String? {
        val firstResult = readUntilBestMove()
        if (firstResult != null) return firstResult

        // 引擎崩溃（isReady = false），尝试重启并重试
        Log.w(TAG, "Engine crashed, attempting restart and retry...")
        if (!tryRestartEngine()) return null

        // 重启成功，重新发送搜索命令
        sendCommand("position fen $fen")
        sendCommand("go movetime $moveTimeMs")
        return readUntilBestMove()
    }

    /**
     * 清空引擎输出流中的残留数据（如上一次搜索的 info 行或 ponder bestmove）
     * 利用 ready() 判断是否有数据，避免阻塞
     */
    private fun drainOutput() {
        val reader = uciReader ?: return
        var drained = 0
        while (reader.ready()) {
            val line = reader.readLine() ?: break
            Log.v(TAG, "drain<<< $line")
            drained++
        }
        if (drained > 0) Log.d(TAG, "Drained $drained lines from engine output buffer")
    }
}
