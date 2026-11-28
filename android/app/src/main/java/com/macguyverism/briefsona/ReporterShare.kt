package com.macguyverism.briefsona

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val magic = byteArrayOf(66, 83, 82, 1)
private fun cipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray): Cipher {
    val spec = PBEKeySpec(password.toCharArray(), salt, 600000, 256)
    val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    return Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(magic)
    }
}

internal fun sealReporter(value: JSONObject, password: String): ByteArray {
    val body = value.toString().toByteArray()
    require(body.size <= 65536 && password.isNotBlank())
    val random = SecureRandom()
    val salt = ByteArray(16).also(random::nextBytes)
    val nonce = ByteArray(12).also(random::nextBytes)
    return magic + salt + nonce + cipher(Cipher.ENCRYPT_MODE, password, salt, nonce).doFinal(body)
}

internal fun openReporter(bytes: ByteArray, password: String): JSONObject {
    require(bytes.size in 49..65584 && bytes.take(4) == magic.toList())
    val data = ByteBuffer.wrap(bytes).apply { position(4) }
    val salt = ByteArray(16).also { data.get(it) }
    val nonce = ByteArray(12).also { data.get(it) }
    val decrypted = cipher(Cipher.DECRYPT_MODE, password, salt, nonce).doFinal(bytes, 32, bytes.size - 32)
    val profile = JSONObject(decrypted.toString(Charsets.UTF_8))
    require(profile.getString("name").isNotBlank() && profile.has("voice") && profile.has("appearance"))
    return profile
}

@Composable
internal fun ReporterShare(profile: JSONObject?, imported: (JSONObject) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var password by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && profile != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(sealReporter(profile,password)) } }
                message = "Reporter exported"
            } catch (_: Exception) { message = "Export failed" }
            finally { password = "" }
        } else password = ""
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val profileData = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(resolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 65584)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    openReporter(bytes, password)
                }
                imported(profileData)
            } catch (_: Exception) { message = "Check the reporter file and passphrase." }
            finally { password = "" }
        } else password = ""
    }
    val action = if (profile == null) "Import reporter" else "Export reporter"
    TextButton(onClick = { dialog = true }) { Text(action) }
    if (message.isNotBlank()) Text(message)
    if (dialog) AlertDialog(onDismissRequest = { dialog = false; password = "" }, title = { Text(action) },
        text = { OutlinedTextField(password, { password = it }, label = { Text("Shared passphrase") },
            singleLine = true, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(enabled = password.isNotBlank(), onClick = {
            dialog = false
            if (profile == null) load.launch(arrayOf("*/*")) else save.launch("Reporter.bsr")
        }) { Text(action) } })
}
