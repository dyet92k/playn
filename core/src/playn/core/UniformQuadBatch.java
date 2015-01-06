/**
 * Copyright 2010-2015 The PlayN Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package playn.core;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import pythagoras.f.AffineTransform;
import static playn.core.GL20.*;

/**
 * A batch which renders quads by stuffing them into a big(ish) GLSL uniform variable. Turns out to
 * work pretty well for 2D rendering as we rarely render more than a modest number of quads before
 * flushing the shader and it allows us to avoid sending a lot of duplicated data as is necessary
 * when rendering quads via a batch of triangles.
 */
public class UniformQuadBatch extends QuadBatch {

  /** Builds the stock quad batch shader program. */
  public class ProgramBuilder extends GLProgramBuilder {

    /** Declares the uniform variables for our shader. */
    public static final String VERT_UNIFS =
      "uniform vec2 u_ScreenSize;\n" +
      "uniform vec4 u_Data[_VEC4S_PER_QUAD_*_MAX_QUADS_];\n";

    /** Declares the attribute variables for our shader. */
    public static final String VERT_ATTRS =
      "attribute vec3 a_Vertex;\n";

    /** Declares the varying variables for our shader. */
    public static final String VERT_VARS =
      "varying vec2 v_TexCoord;\n" +
      "varying vec4 v_Color;\n";

    /** Extracts the values from our data buffer. */
    public static final String VERT_EXTRACTDATA =
      "int index = _VEC4S_PER_QUAD_*int(a_Vertex.z);\n" +
      "vec4 mat = u_Data[index+0];\n" +
      "vec4 txc = u_Data[index+1];\n" +
      "vec4 tcs = u_Data[index+2];\n";

    /** The shader code that computes {@code gl_Position}. */
    public static final String VERT_SETPOS =
      // Transform the vertex.
      "mat3 transform = mat3(\n" +
      "  mat.x, mat.y, 0,\n" +
      "  mat.z, mat.w, 0,\n" +
      "  txc.x, txc.y, 1);\n" +
      "gl_Position = vec4(transform * vec3(a_Vertex.xy, 1.0), 1.0);\n" +
      // Scale from screen coordinates to [0, 2].
      "gl_Position.xy /= u_ScreenSize.xy;\n" +
      // Offset to [-1, 1] and flip y axis to put origin at top-left.
      "gl_Position.x -= 1.0;\n" +
      "gl_Position.y = 1.0 - gl_Position.y;\n";

    /** The shader code that computes {@code v_TexCoord}. */
    public static final String VERT_SETTEX =
      "v_TexCoord = a_Vertex.xy * tcs.xy + txc.zw;\n";

    /** The shader code that computes {@code v_Color}. */
    public static final String VERT_SETCOLOR =
      // tint is encoded as two floats A*R and G*B where A, R, G, B are (0 - 255)
      "float red = mod(tcs.z, 256.0);\n" +
      "float alpha = (tcs.z - red) / 256.0;\n" +
      "float blue = mod(tcs.w, 256.0);\n" +
      "float green = (tcs.w - blue) / 256.0;\n" +
      "v_Color = vec4(red / 255.0, green / 255.0, blue / 255.0, alpha / 255.0);\n";

    /** The GLSL code for our vertex shader. */
    public static final String VERTEX_SHADER =
      VERT_UNIFS +
      VERT_ATTRS +
      VERT_VARS +
      "void main(void) {\n" +
      VERT_EXTRACTDATA +
      VERT_SETPOS +
      VERT_SETTEX +
      VERT_SETCOLOR +
      "}";

    public Program build (GL20 gl, int maxQuads) {
      return new Program(gl, vertexSource(maxQuads), fragmentSource());
    }

    protected String vertexSource (int maxQuads) {
      return vertexSource().
        replace("_MAX_QUADS_", ""+maxQuads).
        replace("_VEC4S_PER_QUAD_", ""+vec4sPerQuad());
    }

    @Override protected String vertexSource () {
      return VERTEX_SHADER;
    }
  }

  public class Program extends GLProgram {
    public final int uTexture;
    public final int uScreenSize;
    public final int uData;
    public final int aVertex;

    public Program (GL20 gl, String vertexSource, String fragmentSource) {
      super(gl, vertexSource, fragmentSource);
      uTexture = gl.glGetUniformLocation(program, "u_Texture");
      assert uTexture >= 0 : "Failed to get u_Texture uniform";
      uScreenSize = gl.glGetUniformLocation(program, "u_ScreenSize");
      assert uScreenSize >= 0 : "Failed to get u_ScreenSize uniform";
      uData = gl.glGetUniformLocation(program, "u_Data");
      assert uData >= 0 : "Failed to get u_Data uniform";
      aVertex = gl.glGetAttribLocation(program, "a_Vertex");
      assert aVertex >= 0 : "Failed to get a_Vertex uniform";
    }
  }

  /**
   * Returns false if the GL context doesn't support sufficient numbers of vertex uniform vectors
   * to allow this shader to run with good performance, true otherwise.
   */
  public static boolean isLikelyToPerform(GL20 gl) {
    int maxVecs = usableMaxUniformVectors(gl);
    // assume we're better off with indexed tris if we can't push at least 16 quads at a time
    return (maxVecs >= 16*BASE_VEC4S_PER_QUAD);
  }

  protected final int maxQuads;
  protected final Program program;
  protected final int verticesId, elementsId;
  protected final float[] data;
  protected int quadCounter;

  public UniformQuadBatch (GL20 gl) {
    super(gl);
    int maxVecs = usableMaxUniformVectors(gl) - extraVec4s();
    if (maxVecs < vec4sPerQuad())
    throw new RuntimeException(
      "GL_MAX_VERTEX_UNIFORM_VECTORS too low: have " + maxVecs +
        ", need at least " + vec4sPerQuad());
    this.maxQuads = maxVecs / vec4sPerQuad();
    this.program = createBuilder().build(gl, maxQuads);

    // create our stock supply of unit quads and stuff them into our buffers
    short[] verts = new short[maxQuads*VERTICES_PER_QUAD*VERTEX_SIZE];
    short[] elems = new short[maxQuads*ELEMENTS_PER_QUAD];
    int vv = 0, ee = 0;
    for (short ii = 0; ii < maxQuads; ii++) {
      verts[vv++] = 0; verts[vv++] = 0; verts[vv++] = ii;
      verts[vv++] = 1; verts[vv++] = 0; verts[vv++] = ii;
      verts[vv++] = 0; verts[vv++] = 1; verts[vv++] = ii;
      verts[vv++] = 1; verts[vv++] = 1; verts[vv++] = ii;
      short base = (short)(ii * VERTICES_PER_QUAD);
      short base0 = base, base1 = ++base, base2 = ++base, base3 = ++base;
      elems[ee++] = base0; elems[ee++] = base1; elems[ee++] = base2;
      elems[ee++] = base1; elems[ee++] = base3; elems[ee++] = base2;
    }

    data = new float[maxQuads*vec4sPerQuad()*4];

    // create our GL buffers
    int[] ids = new int[2];
    gl.glGenBuffers(2, ids, 0);
    verticesId = ids[0]; elementsId = ids[1];

    gl.glBindBuffer(GL_ARRAY_BUFFER, verticesId);
    gl.bufs.setShortBuffer(verts, 0, verts.length);
    gl.glBufferData(GL_ARRAY_BUFFER, verts.length*2, gl.bufs.shortBuffer, GL_STATIC_DRAW);

    gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementsId);
    gl.bufs.setShortBuffer(elems, 0, elems.length);
    gl.glBufferData(GL_ELEMENT_ARRAY_BUFFER, elems.length*2, gl.bufs.shortBuffer, GL_STATIC_DRAW);

    gl.checkError("UniformQuadBatch end ctor");
  }

  @Override public void add (int tint,
                             float m00, float m01, float m10, float m11, float tx, float ty,
                             float x1, float y1, float sx1, float sy1,
                             float x2, float y2, float sx2, float sy2,
                             float x3, float y3, float sx3, float sy3,
                             float x4, float y4, float sx4, float sy4) {
    int pos = quadCounter * vec4sPerQuad();
    float dw = x2 - x1, dh = y3 - y1;
    data[pos++] = m00*dw;
    data[pos++] = m01*dw;
    data[pos++] = m10*dh;
    data[pos++] = m11*dh;
    data[pos++] = tx + m00*x1 + m10*y1;
    data[pos++] = ty + m01*x1 + m11*y1;
    data[pos++] = sx1;
    data[pos++] = sy1;
    data[pos++] = sx2 - sx1;
    data[pos++] = sy3 - sy1;
    data[pos++] = (tint >> 16) & 0xFFFF;
    data[pos++] = tint & 0xFFFF;
    pos = addExtraQuadData(data, pos);
    quadCounter++;

    if (quadCounter >= maxQuads) flush();
  }

  @Override public void begin (float fbufWidth, float fbufHeight) {
    program.activate();
    gl.glUniform2f(program.uScreenSize, fbufWidth/2f, fbufHeight/2f);
    gl.glBindBuffer(GL_ARRAY_BUFFER, verticesId);
    gl.glEnableVertexAttribArray(program.aVertex);
    gl.glVertexAttribPointer(program.aVertex, VERTEX_SIZE, GL_SHORT, false, 0, 0);
    gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementsId);
    gl.glActiveTexture(GL_TEXTURE0);
    gl.glUniform1i(program.uTexture, 0);
    gl.checkError("UniformQuadBatch begin");
  }

  @Override public void flush () {
    if (quadCounter > 0) {
      bindTexture();
      gl.glUniform4fv(program.uData, quadCounter * vec4sPerQuad(), data, 0);
      gl.glDrawElements(GL_TRIANGLES, quadCounter*ELEMENTS_PER_QUAD, GL_UNSIGNED_SHORT, 0);
      gl.checkError("UniformQuadBatch flush");
      quadCounter = 0;
    }
  }

  @Override public void end () {
    flush();
    gl.glDisableVertexAttribArray(program.aVertex);
    gl.checkError("UniformQuadBatch end");
  }

  @Override public void destroy () {
    program.destroy();
    gl.glDeleteBuffers(2, new int[] { verticesId, elementsId }, 0);
  }

  @Override public String toString () {
    return "uquad/" + maxQuads;
  }

  /** Returns the shader program builder used to build our stock shader. */
  protected ProgramBuilder createBuilder () {
    return new ProgramBuilder();
  }

  protected int vec4sPerQuad () {
    return BASE_VEC4S_PER_QUAD;
  }

  /** Returns how many vec4s this shader uses above and beyond those in the base implementation. If
    * you add any extra attributes or uniforms, your subclass will need to account for them here. */
  protected int extraVec4s () {
    return 0;
  }

  protected int addExtraQuadData (float[] data, int pos) {
    return pos;
  }

  private static int usableMaxUniformVectors (GL20 gl) {
    // this returns the maximum number of vec4s; then we subtract one vec2 to account for the
    // uScreenSize uniform, and two more because some GPUs seem to need one for our vec3 attr
    int maxVecs = gl.glGetInteger(GL_MAX_VERTEX_UNIFORM_VECTORS) - 3;
    gl.checkError("glGetInteger(GL_MAX_VERTEX_UNIFORM_VECTORS)");
    return maxVecs;
  }

  private static final int VERTICES_PER_QUAD = 4;
  private static final int ELEMENTS_PER_QUAD = 6;
  private static final int VERTEX_SIZE = 3; // 3 floats per vertex
  private static final int BASE_VEC4S_PER_QUAD = 3; // 3 vec4s per matrix
}