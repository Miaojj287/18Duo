package com.jake.duolauncher

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.*

/** Pyramid lineage: GlassProjection LiveBlurPyramid (MIT). RenderNodes replace shell/OES mirror
 * textures: no screenshots, readback, blocking fences, SurfaceView or second window, so native
 * widgets and Haze stay in the same frame.
 *
 * Pipeline. One atlas holds the untouched sRGB frame (the sharp tile) beside twelve linear-light
 * levels (round 20): H, a half-resolution 2×2 box; F, a full-resolution Gaussian; G0, a
 * half-resolution Gaussian; then nine Gaussian halvings from G0. Levels store sqrt(linear) in their
 * 8 bits (linear light in 8 bits bands in the dark), and the projection reads them with a cubic
 * B-spline fetch, so a wide blur is never a bilinear magnification of a tiny level. Core.35: the
 * projection shader is Apple's screen Wipe ([GlassProjectionMath]); it reads the sharp tile where
 * Apple's blur is below a pixel and blends into the pyramid above it, so the native pixels survive
 * wherever the Wipe leaves them alone. Alpha survives every stage: a translucent layer projects as
 * translucent. */
internal const val GLASS_LINEAR_SHADER = """
uniform shader content;
half4 main(float2 p) {
    half4 s=content.eval(p);
    float a=max(float(s.a),0.0001);
    float3 c=float3(s.rgb)/a;
    c=mix(c/12.92,pow((c+0.055)/1.055,float3(2.4)),step(float3(0.04045),c));
    return half4(half3(sqrt(c*a)),s.a);
}
"""

internal const val GLASS_GAUSSIAN_SHADER = """
uniform shader content;
uniform float2 stepSize;
uniform float2 size;
float4 read(float2 p) { float4 c=float4(content.eval(clamp(p,float2(0.5),size-0.5))); return float4(c.rgb*c.rgb,c.a); }
half4 main(float2 p) {
    float4 c=read(p)*0.227027027;
    c+=(read(p+stepSize*1.384615385)+read(p-stepSize*1.384615385))*0.316216216;
    c+=(read(p+stepSize*3.230769231)+read(p-stepSize*3.230769231))*0.070270270;
    return half4(half3(sqrt(max(c.rgb,float3(0)))),half(c.a));
}
"""

internal const val GLASS_PROJECTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float4 tiles[12];
uniform float levelVar[12];
uniform float2 hingeOrigin;
uniform float2 foldDir;
uniform float leafLength;
uniform float inner;
uniform float wipeAmount;
uniform float2 blurBounds;
uniform float2 shadeBounds;
uniform float light;
uniform float edgeAmount;
uniform float texelPx;
uniform float maxLod;
uniform float2 crossDir;
uniform float crossCenter;
uniform float crossHalf;
uniform float hingeDeg;
uniform float camX;
uniform float restMix;
uniform float uOffset;
uniform float identityMix;
float3 decode(float3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,float3(2.4)),step(float3(0.04045),c));
}
float3 encode(float3 c) {
    c=max(c,float3(0));
    return mix(c*12.92,1.055*pow(c,float3(1.0/2.4))-0.055,step(float3(0.0031308),c));
}
float ss(float e0,float e1,float x) {
    float t=clamp((x-e0)/(e1-e0),0.0,1.0);
    return t*t*(3.0-2.0*t);
}
float4 tap(float4 r,float2 q) {
    float4 c=float4(content.eval(r.xy+clamp(q,float2(0.5),r.zw-0.5)));
    return float4(c.rgb*c.rgb,c.a);
}
// Cubic B-spline fetch from four bilinear taps (Sigg & Hadwiger), in the level's texels.
float4 level(float2 uv,float4 r) {
    float2 t=uv*r.zw-0.5;
    float2 i=floor(t); float2 f=t-i;
    float2 f2=f*f; float2 f3=f2*f;
    float2 w0=(1.0-3.0*f+3.0*f2-f3)/6.0;
    float2 w1=(4.0-6.0*f2+3.0*f3)/6.0;
    float2 w2=(1.0+3.0*f+3.0*f2-3.0*f3)/6.0;
    float2 w3=f3/6.0;
    float2 g0=w0+w1; float2 g1=w2+w3;
    float2 h0=i-0.5+w1/g0; float2 h1=i+1.5+w3/g1;
    return g0.y*(g0.x*tap(r,float2(h0.x,h0.y))+g1.x*tap(r,float2(h1.x,h0.y)))
        +g1.y*(g0.x*tap(r,float2(h0.x,h1.y))+g1.x*tap(r,float2(h1.x,h1.y)));
}
float4 sharp(float2 px) {
    half4 s=content.eval(clamp(px,float2(0.5),size-0.5));
    float a=max(float(s.a),0.0001);
    return float4(decode(float3(s.rgb)/a)*a,float(s.a));
}
// Variance-exact blend of the two levels around sigma^2 (window px), below level 0 with the native pixels.
float4 sampleAt(float2 px,float sigma) {
    float4 s=sharp(px);
    float v=sigma*sigma;
    if(v<=0.0)return s;
    float w=clamp(v/levelVar[0],0.0,1.0);
    float vv=max(v,levelVar[0]);
    float lo=0.0; float vlo=levelVar[0]; float vhi=levelVar[1];
    for(int i=1;i<12;i++) {
        if(vv>=levelVar[i]) { lo=float(i); vlo=levelVar[i]; vhi=i<11?levelVar[i+1]:levelVar[i]; }
    }
    float f=clamp((vv-vlo)/max(vhi-vlo,0.0001),0.0,1.0);
    float hi=min(lo+1.0,11.0);
    float2 uv=px/size;
    float4 b=float4(0);
    for(int i=0;i<12;i++) {
        float k=float(i);
        float wk=k==lo?1.0-f:(k==hi?f:0.0);
        if(wk>0.0) b+=wk*level(uv,tiles[i]);
    }
    return mix(s,b,w);
}
// Apple BlurPass: a cubic B-spline fetch of mip L has (5*4^L-1)/12 texels^2 of variance.
float mipVariance(float l) { return (5.0*pow(4.0,l)-1.0)/12.0; }
// Apple's screen, in the phone's local frame (units of apple.com's model): the inner screen is
// 15.784 long across the fold, the cover 7.735 (from x0 = .235); both leaves turn about the hinge
// axis (0, .2776); the inner surface lies at y = .249 over the content plane y = 0, the cover at
// .828 over y = .8; the camera looks down from (4(1-h), 28, 0).
const float CAM_Y=28.0;
const float2 PIVOT=float2(0.0,0.2776);
half4 main(float2 p) {
    if(p.x<0.0||p.y<0.0||p.x>=size.x||p.y>=size.y)return half4(0);
    float along=dot(p-hingeOrigin,foldDir);
    float across=dot(p-hingeOrigin,crossDir);
    bool isInner=inner>0.5;
    // Screen (mesh) u: inner 0 at the tilting leaf's free edge, .5 at the hinge; cover 0 at the hinge.
    float u=isInner?clamp(0.5-0.5*along/leafLength,0.0,1.0):clamp(along/leafLength,0.0,1.0);
    float foldPx=isInner?2.0*leafLength:leafLength;
    float units=isInner?15.784:7.735;
    float x0=isInner?-7.892:0.235;
    float k=units/foldPx;
    float sy=isInner?0.249:0.828;
    float plane=isInner?0.0:0.8;
    float xf=x0+u*units;
    float zf=(across-crossCenter)*k;
    // The leaf: inner left half turns clockwise by 180 - degrees, the cover counter-clockwise by degrees.
    bool leaf=!isInner||xf<0.0;
    float e=isInner?-radians(180.0-hingeDeg):radians(hingeDeg);
    float2 q=float2(xf,sy)-PIVOT;
    float2 pos=leaf?PIVOT+float2(cos(e)*q.x-sin(e)*q.y,sin(e)*q.x+cos(e)*q.y):float2(xf,sy);
    float2 n=leaf?float2(-sin(e),cos(e)):float2(0.0,1.0);
    // The camera ray through the leaf point; GLSL refract(I, N, 1.0) passes straight through a
    // face seen from the front and reflects off one seen from behind.
    float3 I=normalize(float3(pos.x-camX,pos.y-CAM_Y,zf));
    float dn=dot(n,I.xy);
    float3 D=I-float3(n*(dn+abs(dn)),0.0);
    float dy=abs(D.y)<1e-5?-1e-5:D.y;
    float t=(plane-pos.y)/dy;
    float t0=(sy-plane)/(CAM_Y-sy);
    float X=(pos.x+D.x*t)/(1.0+t0);
    float Z=(zf+D.z*t)/(1.0+t0);
    // Content coordinates with Apple's own scale (lookup .988 / (scale · zoom), the composite's
    // 1/1.12 crop and the frame's .9 inset: about 2 % of margin), eased from the native frame
    // (identity) over the first part of a fold so the first moving frame never jumps.
    // Apple's margins relative to its screen (u .982 / v .976 inner, .992 / .986 cover), applied
    // to this window's own proportions.
    float mu=isInner?0.98205:0.99235;
    float mv=isInner?0.97622:0.98603;
    float cuI=(X-x0)/units;
    float czI=Z/(2.0*crossHalf*k);
    float cuA=0.5+(X*(1.0+t0)-(x0+0.5*units))/units*mu;
    float czA=czI*(1.0+t0)*mv;
    float cu=mix(cuA,cuI,identityMix)+uOffset;
    float cc=mix(czA,czI,identityMix)*2.0*crossHalf+crossCenter;
    cu=mix(cu,u,restMix); cc=mix(cc,across,restMix);
    float srcAlong=isInner?(0.5-cu)*2.0*leafLength:cu*leafLength;
    float2 src=hingeOrigin+foldDir*srcAlong+crossDir*cc;
    // BlurPass works on the content: its distance and its shade follow the content position.
    float dc=isInner?1.0-cu:cu;
    float area=sqrt(clamp((dc-blurBounds.x)/(blurBounds.y-blurBounds.x)*wipeAmount*2.5,0.0,1.0)/0.75);
    float lod=min(8.0*area,maxLod);
    float lo=floor(lod);
    float v=mix(mipVariance(lo),mipVariance(lo+1.0),lod-lo)-mipVariance(0.0);
    float sigma=sqrt(max(2.0*v,0.0))*texelPx;
    float shade=mix(0.25,1.0,ss(1.3,0.9,area));
    // Outside the content the viewer shows its dark frame; the blur carries it in by about sigma.
    float edgeDist=min(min(srcAlong-(isInner?-leafLength:0.0),(isInner?leafLength:leafLength)-srcAlong),
        min(cc-(crossCenter-crossHalf),(crossCenter+crossHalf)-cc));
    float spread=1.25*sigma+0.75;
    float cover=ss(-spread,spread,edgeDist);
    // The composite: wipe and brightness on the screen, the left inner shadow on the content.
    float ds=isInner?1.0-u:u;
    float wipe=mix(1.0,ss(shadeBounds.y,shadeBounds.x,ds),clamp(wipeAmount*2.75,0.0,1.0));
    float edges=mix(1.0,ss(0.05,0.5,cu),edgeAmount);
    float4 col=sampleAt(clamp(src,float2(0.5),size-0.5),sigma);
    float a=max(col.a,0.0001);
    float3 lin=col.rgb/a*cover+float3(0.0015)*(1.0-cover);
    float3 rgb=encode(lin*(shade*shade*light*wipe*edges))*col.a;
    return half4(half3(rgb),half(col.a));
}
"""

@RequiresApi(33)
internal class GlassProjectionRenderer : AutoCloseable {
    private val sourceNode=RenderNode("18Duo-glass-source")
    private val linearNode=RenderNode("18Duo-glass-linear")
    private val levels=Array(GlassProjectionMath.LEVELS) { RenderNode("18Duo-linear-level-$it") }
    private val scratch=Array(GlassProjectionMath.LEVELS-1) { RenderNode("18Duo-gaussian-x-$it") }
    private val output=RenderNode("18Duo-glass-projection")
    private val linear=RuntimeShader(GLASS_LINEAR_SHADER)
    private val horizontal=Array(GlassProjectionMath.LEVELS-1) { RuntimeShader(GLASS_GAUSSIAN_SHADER) }
    private val vertical=Array(GlassProjectionMath.LEVELS-1) { RuntimeShader(GLASS_GAUSSIAN_SHADER) }
    private val projection=RuntimeShader(GLASS_PROJECTION_SHADER)
    private val scope=CanvasDrawScope()
    private var width=0; private var height=0
    private val widths=IntArray(GlassProjectionMath.LEVELS); private val heights=IntArray(GlassProjectionMath.LEVELS)
    private val tiles=FloatArray(GlassProjectionMath.LEVELS*4)
    private var released=false

    private fun configure(w: Int,h: Int) {
        if(width==w && height==h && !released)return
        width=w; height=h; released=false
        sourceNode.setPosition(0,0,w,h); sourceNode.setClipToBounds(true)
        // Decode to linear light (stored as its square root) before any Gaussian; the sharp tile stays sRGB.
        linearNode.setPosition(0,0,w,h); linearNode.setClipToBounds(true)
        linearNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(linear,"content"))
        linearNode.beginRecording().drawRenderNode(sourceNode); linearNode.endRecording()
        // H is an exact half: a 2×2 box, the only minification that bilinear sampling gets right.
        val baseWidth=max(1,(w+1)/2); val baseHeight=max(1,(h+1)/2)
        val column=w+2
        var top=0
        for(i in 0 until GlassProjectionMath.LEVELS) {
            // H and G0 at half resolution, F at full resolution, then one halving per level from G0.
            val shift=max(0,i-2)
            widths[i]=if(i==1)w else max(1,(baseWidth+(1 shl shift)-1) shr shift)
            heights[i]=if(i==1)h else max(1,(baseHeight+(1 shl shift)-1) shr shift)
            tiles[i*4]=column.toFloat(); tiles[i*4+1]=top.toFloat()
            tiles[i*4+2]=widths[i].toFloat(); tiles[i*4+3]=heights[i].toFloat()
            top+=heights[i]+2
            levels[i].setPosition(0,0,widths[i],heights[i]); levels[i].setClipToBounds(true)
            if(i==0) {
                levels[0].beginRecording().apply {
                    scale(widths[0].toFloat()/w,heights[0].toFloat()/h)
                    drawRenderNode(linearNode)
                }; levels[0].endRecording()
            } else {
                // F blurs the full-resolution frame, G0 the box H; every later level halves the one above.
                val from=when(i) { 1 -> linearNode; 2 -> levels[0]; else -> levels[i-1] }
                val fromW=when(i) { 1 -> w; 2 -> widths[0]; else -> widths[i-1] }
                val fromH=when(i) { 1 -> h; 2 -> heights[0]; else -> heights[i-1] }
                scratch[i-1].setPosition(0,0,widths[i],heights[i]); scratch[i-1].setClipToBounds(true)
                horizontal[i-1].setFloatUniform("stepSize",1f,0f)
                vertical[i-1].setFloatUniform("stepSize",0f,1f)
                for(shader in listOf(horizontal[i-1],vertical[i-1]))shader.setFloatUniform("size",widths[i].toFloat(),heights[i].toFloat())
                scratch[i-1].setRenderEffect(RenderEffect.createRuntimeShaderEffect(horizontal[i-1],"content"))
                levels[i].setRenderEffect(RenderEffect.createRuntimeShaderEffect(vertical[i-1],"content"))
                scratch[i-1].beginRecording().apply {
                    scale(widths[i].toFloat()/fromW,heights[i].toFloat()/fromH)
                    drawRenderNode(from)
                }; scratch[i-1].endRecording()
                levels[i].beginRecording().drawRenderNode(scratch[i-1]); levels[i].endRecording()
            }
        }
        projection.setFloatUniform("size",w.toFloat(),h.toFloat())
        projection.setFloatUniform("tiles",tiles)
        projection.setFloatUniform("levelVar",GlassProjectionMath.levelVariances)
        projection.setFloatUniform("maxLod",GlassProjectionMath.MAX_LOD)
        output.setPosition(0,0,column+w,max(h,top))
        output.setClipToBounds(true)
        val canvas=output.beginRecording()
        canvas.drawRenderNode(sourceNode)
        for(i in 0 until GlassProjectionMath.LEVELS) {
            canvas.withTranslation(tiles[i*4],tiles[i*4+1]) { drawRenderNode(levels[i]) }
        }
        output.endRecording()
    }

    fun draw(drawScope: androidx.compose.ui.graphics.drawscope.DrawScope, source: GraphicsLayer,
             hinge: Float, inner: Boolean, leaf: FoldLeafGeometry) {
        configure(drawScope.size.width.roundToInt(),drawScope.size.height.roundToInt())
        val recording=sourceNode.beginRecording()
        scope.draw(drawScope,drawScope.layoutDirection,Canvas(recording),drawScope.size) { drawLayer(source) }
        sourceNode.endRecording()
        val amount=GlassProjectionMath.wipeAmount(hinge,inner)
        val (b0,b1)=GlassProjectionMath.blurBounds(inner); val (s0,s1)=GlassProjectionMath.shadeBounds(inner)
        projection.setFloatUniform("hingeOrigin",leaf.hingeX,leaf.hingeY)
        projection.setFloatUniform("foldDir",leaf.dirX,leaf.dirY)
        projection.setFloatUniform("leafLength",max(1f,leaf.lengthPx))
        projection.setFloatUniform("inner",if(inner)1f else 0f)
        projection.setFloatUniform("wipeAmount",amount)
        projection.setFloatUniform("blurBounds",b0,b1)
        projection.setFloatUniform("shadeBounds",s0,s1)
        projection.setFloatUniform("light",GlassProjectionMath.light(hinge,inner))
        projection.setFloatUniform("edgeAmount",GlassProjectionMath.edgeAmount(hinge,inner))
        projection.setFloatUniform("texelPx",GlassProjectionMath.texelPx(leaf.foldLengthPx(inner),inner))
        // The axis across the fold, centred on the window (the camera sits on its centre line).
        val crossX=-leaf.dirY; val crossY=leaf.dirX
        val w=drawScope.size.width; val h=drawScope.size.height
        projection.setFloatUniform("crossDir",crossX,crossY)
        projection.setFloatUniform("crossCenter",(w/2f-leaf.hingeX)*crossX+(h/2f-leaf.hingeY)*crossY)
        projection.setFloatUniform("crossHalf",(if(crossX!=0f)w else h)/2f)
        projection.setFloatUniform("hingeDeg",GlassProjectionMath.hingeDegrees(hinge))
        projection.setFloatUniform("camX",GlassProjectionMath.cameraX(hinge))
        projection.setFloatUniform("restMix",GlassProjectionMath.restMix(hinge,inner))
        projection.setFloatUniform("uOffset",GlassProjectionMath.contentOffset(hinge,inner))
        projection.setFloatUniform("identityMix",GlassProjectionMath.identityMix(hinge,inner))
        // RenderNode cannot observe mutable RuntimeShader uniforms without a fresh effect.
        output.setRenderEffect(RenderEffect.createRuntimeShaderEffect(projection,"content"))
        val target=drawScope.drawContext.canvas.nativeCanvas
        target.withClip(0f,0f,drawScope.size.width,drawScope.size.height) { drawRenderNode(output) }
    }

    override fun close() {
        if(released)return
        output.discardDisplayList(); sourceNode.discardDisplayList(); linearNode.discardDisplayList()
        levels.forEach { it.discardDisplayList() }; scratch.forEach { it.discardDisplayList() }
        released=true
    }
}

/** Apple's screen Wipe over this window ([GlassProjectionMath]) at Hinge.position [hinge]. [hinge]
 * and [rotation] (`Display.getRotation()`, round 12: the hinge edge follows the physical hinge
 * under an inherited rotation) are read in the draw phase only; at rest nothing is recorded or
 * retained. */
@RequiresApi(33)
@Composable
internal fun Modifier.glassProjection(hinge: () -> Float, inner: Boolean,
    rotation: () -> Int = { android.view.Surface.ROTATION_0 }): Modifier {
    val source=rememberGraphicsLayer()
    // A shader the device cannot compile must cost the effect, never Home.
    val renderer=remember { runCatching { GlassProjectionRenderer() }
        .onFailure { android.util.Log.e("DuoFoldTransition", "glass projection unavailable", it) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return drawWithContent {
        val h=hinge()
        if(renderer==null || !GlassProjectionMath.active(h,inner) || size.width<2f || size.height<2f ||
            !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            // Exact native endpoint, with no blur buffers retained by resting panes.
            renderer?.close(); drawContent()
        } else {
            source.record { this@drawWithContent.drawContent() }
            // The Wipe covers the whole window (the inner blur reaches into the held half and the
            // inner brightness dims all of it), so the native frame is not drawn underneath.
            renderer.draw(this,source,h,inner,FoldLeafGeometry.forWindow(size.width,size.height,inner,rotation()))
        }
    }
}
