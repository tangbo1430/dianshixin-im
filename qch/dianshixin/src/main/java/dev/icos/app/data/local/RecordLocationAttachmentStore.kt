package dev.icos.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import dev.icos.app.domain.RecordCoordinates
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class RecordLocationAttachment(val ownerId:String,val epochId:String,val recordId:String,val eventId:String,val revision:Long,val latitude:String,val longitude:String,val label:String)

internal object RecordLocationAttachmentStore {
    private const val KEY_PREFIX="icos.record-location.v1"; private const val MAX_LABEL_LENGTH=160
    fun validateCoordinate(latitude:String,longitude:String)=RecordCoordinates.validate(latitude,longitude)
    fun read(context:Context,ownerId:String,epochId:String,recordId:String):RecordLocationAttachment? {
        val file=file(context,ownerId,epochId,recordId); if(!file.isFile)return null
        return runCatching {
            val packed=file.readBytes(); require(packed.size in 29..16_384&&packed[0]==1.toByte())
            val key=key(ownerId,epochId,false)?:return null
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,packed,1,12));cipher.updateAAD(aad(ownerId,epochId,recordId))
            val payload=JSONObject(String(cipher.doFinal(packed,13,packed.size-13),Charsets.UTF_8))
            val attachment=RecordLocationAttachment(payload.getString("ownerId"),payload.getString("epochId"),payload.getString("recordId"),payload.getString("eventId"),payload.getLong("revision"),payload.getString("latitude"),payload.getString("longitude"),payload.getString("label"))
            require(attachment.ownerId==ownerId&&attachment.epochId==epochId&&attachment.recordId==recordId);validateCoordinate(attachment.latitude,attachment.longitude);require(attachment.label.length<=MAX_LABEL_LENGTH);attachment
        }.getOrNull()
    }
    fun save(context:Context,attachment:RecordLocationAttachment) {
        require(attachment.revision>0&&attachment.ownerId.isNotBlank()&&attachment.epochId.isNotBlank())
        val (latitude,longitude)=validateCoordinate(attachment.latitude,attachment.longitude);require(attachment.label.length<=MAX_LABEL_LENGTH&&attachment.label.none{it=='\u0000'})
        val normalized=attachment.copy(latitude=latitude,longitude=longitude,label=attachment.label.trim())
        val payload=JSONObject().put("ownerId",normalized.ownerId).put("epochId",normalized.epochId).put("recordId",normalized.recordId).put("eventId",normalized.eventId).put("revision",normalized.revision).put("latitude",normalized.latitude).put("longitude",normalized.longitude).put("label",normalized.label).toString().toByteArray(Charsets.UTF_8)
        val key=key(normalized.ownerId,normalized.epochId,true)?:error("Location key unavailable")
        try { val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad(normalized.ownerId,normalized.epochId,normalized.recordId));writeAtomic(file(context,normalized.ownerId,normalized.epochId,normalized.recordId),byteArrayOf(1)+cipher.iv+cipher.doFinal(payload)) } finally {payload.fill(0)}
    }
    fun delete(context:Context,ownerId:String,epochId:String,recordId:String) { val target=file(context,ownerId,epochId,recordId);if(target.exists())check(target.delete()){ "无法删除本地位置附加信息" };target.parentFile?.let{if(it.isDirectory&&it.list()?.isEmpty()==true)it.delete()} }
    private fun key(owner:String,epoch:String,create:Boolean):SecretKey? { val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val alias="$KEY_PREFIX.$owner.$epoch";(store.getKey(alias,null) as? SecretKey)?.let{return it};if(!create)return null;return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());generateKey()} }
    private fun directory(context:Context,owner:String,epoch:String)=File(context.applicationContext.noBackupFilesDir,"record-location/$owner/$epoch")
    private fun file(context:Context,owner:String,epoch:String,record:String):File {require(owner.matches(Regex("[0-9a-fA-F-]{36}"))&&epoch.matches(Regex("[0-9a-fA-F-]{36}"))&&record.matches(Regex("[0-9a-fA-F-]{36}")));val digest=MessageDigest.getInstance("SHA-256").digest(record.toByteArray(Charsets.US_ASCII)).joinToString(""){ "%02x".format(it) };return File(directory(context,owner,epoch),"$digest.loc")}
    private fun aad(owner:String,epoch:String,record:String)="icos.record-location.v1:$owner:$epoch:$record".toByteArray(Charsets.UTF_8)
    private fun writeAtomic(destination:File,bytes:ByteArray) {val parent=destination.parentFile!!;check(parent.isDirectory||parent.mkdirs());val temporary=File(parent,destination.name+".pending");try{FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()};Os.rename(temporary.absolutePath,destination.absolutePath);val fd=Os.open(parent.absolutePath,OsConstants.O_RDONLY,0);try{Os.fsync(fd)}finally{Os.close(fd)}}finally{bytes.fill(0);temporary.delete()}}
}
