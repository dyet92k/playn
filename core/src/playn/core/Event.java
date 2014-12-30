/**
 * Copyright 2011 The PlayN Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package playn.core;

/**
 * Defines an event abstraction used in various places in PlayN.
 */
public abstract class Event {

  /** A flag indicating that the default OS behavior for an event should be prevented. */
  public static final int F_PREVENT_DEFAULT = 1 << 0;

  /** The base for all input events. */
  public static class Input extends Event {
    // TODO(mdb): a mechanism to determine which modifier keys are pressed, if any

    /**
     * The flags set for this event. See {@link #isSet}, {@link #setFlag} and {@link #clearFlag}.
     */
    public int flags;

    /**
     * The time at which this event was generated, in milliseconds. This time's magnitude is not
     * portable (i.e. may not be the same across backends), clients must interpret it as only a
     * monotonically increasing value.
     */
    public final double time;

    /** Returns whether the {@code flag} bit is set. */
    public boolean isSet (int flag) {
      return (flags & flag) != 0;
    }

    /** Sets the {@code flag} bit. */
    public void setFlag (int flag) {
      flags |= flag;
    }

    /** Clears the {@code flag} bit. */
    public void clearFlag (int flag) {
      flags &= ~flag;
    }

    /** Sets or clears {@code flag} based on {@code on}. */
    public void updateFlag (int flag, boolean on) {
      if (on) setFlag(flag);
      else clearFlag(flag);
    }

    @Override public String toString () {
      StringBuilder builder = new StringBuilder(name()).append('[');
      addFields(builder);
      return builder.append(']').toString();
    }

    protected Input (int flags, double time) {
      this.flags = flags;
      this.time = time;
    }

    protected String name () {
      return "Input";
    }

    protected void addFields (StringBuilder builder) {
      builder.append("time=").append(time).append(", flags=").append(flags);
    }
  }

  /** The base for all input events with a screen position. */
  public static class XY extends Input implements pythagoras.f.XY {

    /** The screen x-coordinate associated with this event. */
    public final float x;

    /** The screen y-coordinate associated with this event. */
    public final float y;

    @Override public float x () {
      return x;
    }
    @Override public float y () {
      return y;
    }

    protected XY (int flags, double time, float x, float y) {
      super(flags, time);
      this.x = x;
      this.y = y;
    }

    @Override protected String name () {
      return "XY";
    }

    @Override protected void addFields (StringBuilder builder) {
      super.addFields(builder);
      builder.append(", x=").append(x).append(", y=").append(y);
    }
  }
}
