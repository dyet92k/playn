/**
 * Copyright 2010 The PlayN Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package playn.java;

import java.awt.EventQueue;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.imageio.ImageIO;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import playn.core.AbstractAssets;
import playn.core.Image;
import playn.core.PlayN;
import playn.core.ResourceCallback;
import playn.core.Sound;
import playn.core.gl.Scale;

/**
 * Loads Java assets via the classpath.
 */
public class JavaAssets extends AbstractAssets {

  /** Makes asset loading asynchronous to mimic the behavior of the HTML backend. */
  private static final boolean asyncLoad = Boolean.getBoolean("playn.java.asyncLoad");

  private final JavaPlatform platform;
  private String pathPrefix = "";

  static void doResourceAction(Runnable action) {
    if (asyncLoad)
      EventQueue.invokeLater(action);
    else
      action.run();
  }

  public JavaAssets (JavaPlatform platform) {
    this.platform = platform;
  }

  /**
   * Configures the prefix prepended to asset paths before fetching them from the classpath. For
   * example, if your assets are in {@code src/main/java/com/mygame/assets} (or in {@code
   * src/main/resources/com/mygame/assets}), you can pass {@code com/mygame/assets} to this method
   * and then load your assets without prefixing their path with that value every time. The value
   * supplied to this method should not contain leading or trailing slashes. Note that this prefix
   * should always use '/' as a path separator as it is used to construct URLs, not filesystem
   * paths.
   */
  public void setPathPrefix(String prefix) {
    if (prefix.startsWith("/") || prefix.endsWith("/")) {
      throw new IllegalArgumentException("Prefix must not start or end with '/'.");
    }
    pathPrefix = (prefix.length() == 0) ? prefix : (prefix + "/");
  }

  /**
   * Returns the currently configured path prefix. Note that this value will always have a trailing
   * slash.
   */
  public String getPathPrefix() {
    return pathPrefix;
  }

  InputStream getAssetStream(String path) throws IOException {
    InputStream in = getClass().getClassLoader().getResourceAsStream(pathPrefix + path);
    if (in == null) {
      throw new FileNotFoundException(path);
    }
    return in;
  }

  @Override
  protected Image doGetImage(String path) {
    JavaGraphics graphics = platform.graphics();
    Exception error = null;
    for (Scale.ScaledResource rsrc : graphics.ctx().scale.getScaledResources(pathPrefix + path)) {
      try {
        return graphics.createStaticImage(ImageIO.read(requireResource(rsrc.path)), rsrc.scale);
      } catch (FileNotFoundException fnfe) {
        error = fnfe; // keep going, checking for lower resolution images
      } catch (Exception e) {
        error = e;
        break; // the image was broken not missing, stop here
      }
    }
    PlayN.log().warn("Could not load image: " + pathPrefix + path, error);
    return graphics.createErrorImage(error != null ? error : new FileNotFoundException(path));
  }

  @Override
  protected Sound doGetSound(String path) {
    path += ".mp3";
    InputStream in = getClass().getClassLoader().getResourceAsStream(pathPrefix + path);
    if (in == null) {
      PlayN.log().warn("Could not find sound " + pathPrefix + path);
      return platform.audio().createNoopSound();
    } else {
      return platform.audio().createSound(path, in);
    }
  }

  @Override
  protected void doGetText(final String path, final ResourceCallback<String> callback) {
    doResourceAction(new Runnable() {
      public void run() {
        try {
          callback.done(Resources.toString(requireResource(pathPrefix + path), Charsets.UTF_8));
        } catch (Exception e) {
          callback.error(e);
        }
      }
    });
  }

  protected URL requireResource(String path) throws FileNotFoundException {
    URL url = getClass().getClassLoader().getResource(path);
    if (url == null) {
      throw new FileNotFoundException(path);
    }
    return url;
  }
}
