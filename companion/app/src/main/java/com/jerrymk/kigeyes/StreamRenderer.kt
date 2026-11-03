package com.jerrymk.kigeyes

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns a permanent 720p camera sink. UI lifetime never changes Camera2's outputs. */
class StreamRenderer(private val sensorOrientation: Int,private val fail: (String)->Unit): Closeable {
    private val thread=HandlerThread("stream-gl").apply { start() }
    private val handler=Handler(thread.looper)
    private var display=EGL14.EGL_NO_DISPLAY
    private var context=EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig?=null
    private var pbuffer=EGL14.EGL_NO_SURFACE
    private var output=EGL14.EGL_NO_SURFACE
    private var uiWindow=EGL14.EGL_NO_SURFACE
    private var ui: PreviewTarget?=null
    private lateinit var texture: SurfaceTexture
    lateinit var cameraSurface: Surface;private set
    private var textureId=0
    private var program=0
    private val transform=FloatArray(16)
    private val positions=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0) }
    private val coords=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(CameraTextureCoordinates.landscape(sensorOrientation));position(0) }
    private val previewCoords=(0..3).map { turn -> ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(CameraTextureCoordinates.landscape((sensorOrientation-turn*90+360)%360));position(0)
    } }
    @Volatile private var closed=false
    @Volatile var rendered=0L;private set
    @Volatile var previewAttached=false;private set
    fun start(encoderSurface: Surface) {
        val ready=CountDownLatch(1);var failure: Throwable?=null
        handler.post {
            try {
                display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display,null,0,null,0)) { "EGL initialization failed" }
                val configs=arrayOfNulls<EGLConfig>(1);val count=IntArray(1)
                val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,0x3142,1,EGL14.EGL_NONE)
                check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) && count[0]>0) { "No recordable EGL config" };config=configs[0]
                context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
                check(context!=EGL14.EGL_NO_CONTEXT)
                pbuffer=EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
                current(pbuffer)
                output=window(encoderSurface)
                val names=IntArray(1);GLES20.glGenTextures(1,names,0);textureId=names[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPosition; attribute vec2 aTex; uniform mat4 uMatrix; varying vec2 vTex; void main(){gl_Position=vec4(aPosition,0.0,1.0);vTex=(uMatrix*vec4(aTex,0.0,1.0)).xy;}")
                val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTex;void main(){gl_FragColor=texture2D(uTexture,vTex);}")
                program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program)
                val ok=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);check(ok[0]!=0) { GLES20.glGetProgramInfoLog(program) }
                GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment)
                texture=SurfaceTexture(textureId).apply { setDefaultBufferSize(1280,720);setOnFrameAvailableListener({ drawFrame() },handler) }
                cameraSurface=Surface(texture)
            } catch(e: Throwable) { failure=e } finally { ready.countDown() }
        }
        check(ready.await(8,TimeUnit.SECONDS)) { "Stream renderer initialization timed out" }
        failure?.let { throw IllegalStateException("Stream renderer: ${it.message}",it) }
    }
    fun preview(target: PreviewTarget?) {
        if(target!=null && !target.retain()) return
        if(!handler.post {
            if(closed) { target?.release();return@post }
            current(pbuffer);releaseUi()
            if(target!=null) {
                try { uiWindow=window(target.surface);ui=target;previewAttached=true }
                catch(_: Exception) { target.release();uiWindow=EGL14.EGL_NO_SURFACE }
            }
        }) target?.release()
    }
    private fun window(surface: Surface): EGLSurface {
        val value=EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
        check(value!=EGL14.EGL_NO_SURFACE) { "EGL surface: ${EGL14.eglGetError()}" };return value
    }
    private fun current(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "EGL make-current failed" } }
    private fun shader(type: Int,source: String): Int {
        val s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s)
        val ok=IntArray(1);GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);check(ok[0]!=0) { GLES20.glGetShaderInfoLog(s) };return s
    }
    private fun draw(surface: EGLSurface) {
        current(surface)
        val width=IntArray(1);val height=IntArray(1)
        EGL14.eglQuerySurface(display,surface,EGL14.EGL_WIDTH,width,0);EGL14.eglQuerySurface(display,surface,EGL14.EGL_HEIGHT,height,0)
        // Preview follows its display, not the physical pose used for remote stream metadata.
        // This also stays upright during Android's rotation animation and in multi-window.
        val rotation=if(surface==uiWindow)PreviewGeometry.rotation(sensorOrientation,ui?.displayDegrees ?: 0) else 0
        val (vw,vh)=PreviewGeometry.fit(width[0],height[0],rotation)
        GLES20.glViewport(0,0,width[0],height[0]);GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport((width[0]-vw)/2,(height[0]-vh)/2,vw,vh);GLES20.glUseProgram(program)
        val position=GLES20.glGetAttribLocation(program,"aPosition");val coord=GLES20.glGetAttribLocation(program,"aTex")
        GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,positions)
        GLES20.glEnableVertexAttribArray(coord);GLES20.glVertexAttribPointer(coord,2,GLES20.GL_FLOAT,false,0,if(surface==uiWindow)previewCoords[rotation/90] else coords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMatrix"),1,false,transform,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }
    private fun drawFrame() {
        if(closed) return
        try {
            current(pbuffer);texture.updateTexImage();texture.getTransformMatrix(transform)
            if(BuildConfig.DEBUG && rendered==0L) android.util.Log.i("KigEyesGeometry","Camera texture transform: ${transform.joinToString()}")
            draw(output);EGLExt.eglPresentationTimeANDROID(display,output,texture.timestamp)
            check(EGL14.eglSwapBuffers(display,output)) { "Stream encoder surface stopped accepting frames" };rendered++
            if(uiWindow!=EGL14.EGL_NO_SURFACE) {
                // A disappearing UI must never take down the recording/streaming outputs.
                try { draw(uiWindow);check(EGL14.eglSwapBuffers(display,uiWindow)) }
                catch(_: Exception) { current(pbuffer);releaseUi() }
            }
        } catch(e: Exception) { if(!closed) { closed=true;fail("Stream renderer: ${e.message}") } }
    }
    private fun releaseUi() {
        if(uiWindow!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,uiWindow)
        uiWindow=EGL14.EGL_NO_SURFACE;ui?.release();ui=null;previewAttached=false
    }
    fun shutdown(complete: ()->Unit) {
        if(!handler.post {
            closed=true
            runCatching {
                if(display!=EGL14.EGL_NO_DISPLAY && context!=EGL14.EGL_NO_CONTEXT) {
                    current(pbuffer);releaseUi()
                    if(::cameraSurface.isInitialized) cameraSurface.release()
                    if(::texture.isInitialized) { texture.setOnFrameAvailableListener(null);texture.release() }
                    if(program!=0) GLES20.glDeleteProgram(program)
                    GLES20.glDeleteTextures(1,intArrayOf(textureId),0)
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                    if(output!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,output)
                    if(pbuffer!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,pbuffer)
                    EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display)
                }
            }
            complete();thread.quitSafely()
        }) complete()
    }
    override fun close() {
        val done=CountDownLatch(1);shutdown { done.countDown() };done.await(3,TimeUnit.SECONDS)
    }
}
