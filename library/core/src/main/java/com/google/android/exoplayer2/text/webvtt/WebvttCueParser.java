/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.text.webvtt;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Parser for WebVTT cues. (https://w3c.github.io/webvtt/#cues) */
public final class WebvttCueParser {

  /**
   * Valid values for {@link WebvttCueInfoBuilder#textAlignment}.
   *
   * <p>We use a custom list (and not {@link Layout.Alignment} directly) in order to include both
   * {@code START}/{@code LEFT} and {@code END}/{@code RIGHT}. The distinction is important for
   * {@link WebvttCueInfoBuilder#derivePosition(int)}.
   *
   * <p>These correspond to the valid values for the 'align' cue setting in the <a
   * href="https://www.w3.org/TR/webvtt1/#webvtt-cue-text-alignment">WebVTT spec</a>.
   */
  @Documented
  @Retention(SOURCE)
  @IntDef({
    TEXT_ALIGNMENT_START,
    TEXT_ALIGNMENT_CENTER,
    TEXT_ALIGNMENT_END,
    TEXT_ALIGNMENT_LEFT,
    TEXT_ALIGNMENT_RIGHT
  })
  private @interface TextAlignment {}

  /**
   * See WebVTT's <a
   * href="https://www.w3.org/TR/webvtt1/#webvtt-cue-start-alignment">align:start</a>.
   */
  private static final int TEXT_ALIGNMENT_START = 1;

  /**
   * See WebVTT's <a
   * href="https://www.w3.org/TR/webvtt1/#webvtt-cue-center-alignment">align:center</a>.
   */
  private static final int TEXT_ALIGNMENT_CENTER = 2;

  /**
   * See WebVTT's <a href="https://www.w3.org/TR/webvtt1/#webvtt-cue-end-alignment">align:end</a>.
   */
  private static final int TEXT_ALIGNMENT_END = 3;

  /**
   * See WebVTT's <a href="https://www.w3.org/TR/webvtt1/#webvtt-cue-left-alignment">align:left</a>.
   */
  private static final int TEXT_ALIGNMENT_LEFT = 4;

  /**
   * See WebVTT's <a
   * href="https://www.w3.org/TR/webvtt1/#webvtt-cue-right-alignment">align:right</a>.
   */
  private static final int TEXT_ALIGNMENT_RIGHT = 5;

  public static final Pattern CUE_HEADER_PATTERN = Pattern
      .compile("^(\\S+)\\s+-->\\s+(\\S+)(.*)?$");
  private static final Pattern CUE_SETTING_PATTERN = Pattern.compile("(\\S+?):(\\S+)");

  private static final char CHAR_LESS_THAN = '<';
  private static final char CHAR_GREATER_THAN = '>';
  private static final char CHAR_SLASH = '/';
  private static final char CHAR_AMPERSAND = '&';
  private static final char CHAR_SEMI_COLON = ';';
  private static final char CHAR_SPACE = ' ';

  private static final String ENTITY_LESS_THAN = "lt";
  private static final String ENTITY_GREATER_THAN = "gt";
  private static final String ENTITY_AMPERSAND = "amp";
  private static final String ENTITY_NON_BREAK_SPACE = "nbsp";

  private static final String TAG_BOLD = "b";
  private static final String TAG_ITALIC = "i";
  private static final String TAG_UNDERLINE = "u";
  private static final String TAG_CLASS = "c";
  private static final String TAG_VOICE = "v";
  private static final String TAG_LANG = "lang";

  private static final int STYLE_BOLD = Typeface.BOLD;
  private static final int STYLE_ITALIC = Typeface.ITALIC;

  /* package */ static final float DEFAULT_POSITION = 0.5f;

  private static final String TAG = "WebvttCueParser";

  /**
   * Parses the next valid WebVTT cue in a parsable array, including timestamps, settings and text.
   *
   * @param webvttData Parsable WebVTT file data.
   * @param styles List of styles defined by the CSS style blocks preceding the cues.
   * @return The parsed cue info, or null if no valid cue was found.
   */
  @Nullable
  public static WebvttCueInfo parseCue(ParsableByteArray webvttData, List<WebvttCssStyle> styles) {
    @Nullable String firstLine = webvttData.readLine();
    if (firstLine == null) {
      return null;
    }
    Matcher cueHeaderMatcher = WebvttCueParser.CUE_HEADER_PATTERN.matcher(firstLine);
    if (cueHeaderMatcher.matches()) {
      // We have found the timestamps in the first line. No id present.
      return parseCue(null, cueHeaderMatcher, webvttData, styles);
    }
    // The first line is not the timestamps, but could be the cue id.
    @Nullable String secondLine = webvttData.readLine();
    if (secondLine == null) {
      return null;
    }
    cueHeaderMatcher = WebvttCueParser.CUE_HEADER_PATTERN.matcher(secondLine);
    if (cueHeaderMatcher.matches()) {
      // We can do the rest of the parsing, including the id.
      return parseCue(firstLine.trim(), cueHeaderMatcher, webvttData, styles);
    }
    return null;
  }

  /**
   * Parses a string containing a list of cue settings.
   *
   * @param cueSettingsList String containing the settings for a given cue.
   * @return The cue settings parsed into a {@link Cue.Builder}.
   */
  /* package */ static Cue.Builder parseCueSettingsList(String cueSettingsList) {
    WebvttCueInfoBuilder builder = new WebvttCueInfoBuilder();
    parseCueSettingsList(cueSettingsList, builder);
    return builder.toCueBuilder();
  }

  /** Create a new {@link Cue} containing {@code text} and with WebVTT default values. */
  /* package */ static Cue newCueForText(CharSequence text) {
    WebvttCueInfoBuilder infoBuilder = new WebvttCueInfoBuilder();
    infoBuilder.text = text;
    return infoBuilder.toCueBuilder().build();
  }

  /**
   * Parses the text payload of a WebVTT Cue and returns it as a styled {@link SpannedString}.
   *
   * @param id ID of the cue, {@code null} if it is not present.
   * @param markup The markup text to be parsed.
   * @param styles List of styles defined by the CSS style blocks preceding the cues.
   * @return The styled cue text.
   */
  /* package */ static SpannedString parseCueText(
      @Nullable String id, String markup, List<WebvttCssStyle> styles) {
    SpannableStringBuilder spannedText = new SpannableStringBuilder();
    ArrayDeque<StartTag> startTagStack = new ArrayDeque<>();
    List<StyleMatch> scratchStyleMatches = new ArrayList<>();
    int pos = 0;
    while (pos < markup.length()) {
      char curr = markup.charAt(pos);
      switch (curr) {
        case CHAR_LESS_THAN:
          if (pos + 1 >= markup.length()) {
            pos++;
            break; // avoid ArrayOutOfBoundsException
          }
          int ltPos = pos;
          boolean isClosingTag = markup.charAt(ltPos + 1) == CHAR_SLASH;
          pos = findEndOfTag(markup, ltPos + 1);
          boolean isVoidTag = markup.charAt(pos - 2) == CHAR_SLASH;
          String fullTagExpression = markup.substring(ltPos + (isClosingTag ? 2 : 1),
              isVoidTag ? pos - 2 : pos - 1);
          if (fullTagExpression.trim().isEmpty()) {
            continue;
          }
          String tagName = getTagName(fullTagExpression);
          if (!isSupportedTag(tagName)) {
            continue;
          }
          if (isClosingTag) {
            StartTag startTag;
            do {
              if (startTagStack.isEmpty()) {
                break;
              }
              startTag = startTagStack.pop();
              applySpansForTag(id, startTag, spannedText, styles, scratchStyleMatches);
            } while(!startTag.name.equals(tagName));
          } else if (!isVoidTag) {
            startTagStack.push(StartTag.buildStartTag(fullTagExpression, spannedText.length()));
          }
          break;
        case CHAR_AMPERSAND:
          int semiColonEndIndex = markup.indexOf(CHAR_SEMI_COLON, pos + 1);
          int spaceEndIndex = markup.indexOf(CHAR_SPACE, pos + 1);
          int entityEndIndex = semiColonEndIndex == -1 ? spaceEndIndex
              : (spaceEndIndex == -1 ? semiColonEndIndex
                  : Math.min(semiColonEndIndex, spaceEndIndex));
          if (entityEndIndex != -1) {
            applyEntity(markup.substring(pos + 1, entityEndIndex), spannedText);
            if (entityEndIndex == spaceEndIndex) {
              spannedText.append(" ");
            }
            pos = entityEndIndex + 1;
          } else {
            spannedText.append(curr);
            pos++;
          }
          break;
        default:
          spannedText.append(curr);
          pos++;
          break;
      }
    }
    // apply unclosed tags
    while (!startTagStack.isEmpty()) {
      applySpansForTag(id, startTagStack.pop(), spannedText, styles, scratchStyleMatches);
    }
    applySpansForTag(id, StartTag.buildWholeCueVirtualTag(), spannedText, styles,
        scratchStyleMatches);
    return SpannedString.valueOf(spannedText);
  }

  // Internal methods

  @Nullable
  private static WebvttCueInfo parseCue(
      @Nullable String id,
      Matcher cueHeaderMatcher,
      ParsableByteArray webvttData,
      List<WebvttCssStyle> styles) {
    WebvttCueInfoBuilder builder = new WebvttCueInfoBuilder();
    try {
      // Parse the cue start and end times.
      builder.startTimeUs = WebvttParserUtil.parseTimestampUs(cueHeaderMatcher.group(1));
      builder.endTimeUs = WebvttParserUtil.parseTimestampUs(cueHeaderMatcher.group(2));
    } catch (NumberFormatException e) {
      Log.w(TAG, "Skipping cue with bad header: " + cueHeaderMatcher.group());
      return null;
    }

    parseCueSettingsList(cueHeaderMatcher.group(3), builder);

    // Parse the cue text.
    StringBuilder textBuilder = new StringBuilder();
    for (String line = webvttData.readLine();
        !TextUtils.isEmpty(line);
        line = webvttData.readLine()) {
      if (textBuilder.length() > 0) {
        textBuilder.append("\n");
      }
      textBuilder.append(line.trim());
    }
    builder.text = parseCueText(id, textBuilder.toString(), styles);
    return builder.build();
  }

  private static void parseCueSettingsList(String cueSettingsList, WebvttCueInfoBuilder builder) {
    // Parse the cue settings list.
    Matcher cueSettingMatcher = CUE_SETTING_PATTERN.matcher(cueSettingsList);

    while (cueSettingMatcher.find()) {
      String name = cueSettingMatcher.group(1);
      String value = cueSettingMatcher.group(2);
      try {
        if ("line".equals(name)) {
          parseLineAttribute(value, builder);
        } else if ("align".equals(name)) {
          builder.textAlignment = parseTextAlignment(value);
        } else if ("position".equals(name)) {
          parsePositionAttribute(value, builder);
        } else if ("size".equals(name)) {
          builder.size = WebvttParserUtil.parsePercentage(value);
        } else if ("vertical".equals(name)) {
          builder.verticalType = parseVerticalAttribute(value);
        } else {
          Log.w(TAG, "Unknown cue setting " + name + ":" + value);
        }
      } catch (NumberFormatException e) {
        Log.w(TAG, "Skipping bad cue setting: " + cueSettingMatcher.group());
      }
    }
  }

  private static void parseLineAttribute(String s, WebvttCueInfoBuilder builder) {
    int commaIndex = s.indexOf(',');
    if (commaIndex != -1) {
      builder.lineAnchor = parsePositionAnchor(s.substring(commaIndex + 1));
      s = s.substring(0, commaIndex);
    }
    if (s.endsWith("%")) {
      builder.line = WebvttParserUtil.parsePercentage(s);
      builder.lineType = Cue.LINE_TYPE_FRACTION;
    } else {
      int lineNumber = Integer.parseInt(s);
      if (lineNumber < 0) {
        // WebVTT defines line -1 as last visible row when lineAnchor is ANCHOR_TYPE_START, where-as
        // Cue defines it to be the first row that's not visible.
        lineNumber--;
      }
      builder.line = lineNumber;
      builder.lineType = Cue.LINE_TYPE_NUMBER;
    }
  }

  private static void parsePositionAttribute(String s, WebvttCueInfoBuilder builder) {
    int commaIndex = s.indexOf(',');
    if (commaIndex != -1) {
      builder.positionAnchor = parsePositionAnchor(s.substring(commaIndex + 1));
      s = s.substring(0, commaIndex);
    }
    builder.position = WebvttParserUtil.parsePercentage(s);
  }

  @Cue.AnchorType
  private static int parsePositionAnchor(String s) {
    switch (s) {
      case "start":
        return Cue.ANCHOR_TYPE_START;
      case "center":
      case "middle":
        return Cue.ANCHOR_TYPE_MIDDLE;
      case "end":
        return Cue.ANCHOR_TYPE_END;
      default:
        Log.w(TAG, "Invalid anchor value: " + s);
        return Cue.TYPE_UNSET;
    }
  }

  @Cue.VerticalType
  private static int parseVerticalAttribute(String s) {
    switch (s) {
      case "rl":
        return Cue.VERTICAL_TYPE_RL;
      case "lr":
        return Cue.VERTICAL_TYPE_LR;
      default:
        Log.w(TAG, "Invalid 'vertical' value: " + s);
        return Cue.TYPE_UNSET;
    }
  }

  @TextAlignment
  private static int parseTextAlignment(String s) {
    switch (s) {
      case "start":
        return TEXT_ALIGNMENT_START;
      case "left":
        return TEXT_ALIGNMENT_LEFT;
      case "center":
      case "middle":
        return TEXT_ALIGNMENT_CENTER;
      case "end":
        return TEXT_ALIGNMENT_END;
      case "right":
        return TEXT_ALIGNMENT_RIGHT;
      default:
        Log.w(TAG, "Invalid alignment value: " + s);
        // Default value: https://www.w3.org/TR/webvtt1/#webvtt-cue-text-alignment
        return TEXT_ALIGNMENT_CENTER;
    }
  }

  /**
   * Find end of tag (&gt;). The position returned is the position of the &gt; plus one (exclusive).
   *
   * @param markup The WebVTT cue markup to be parsed.
   * @param startPos The position from where to start searching for the end of tag.
   * @return The position of the end of tag plus 1 (one).
   */
  private static int findEndOfTag(String markup, int startPos) {
    int index = markup.indexOf(CHAR_GREATER_THAN, startPos);
    return index == -1 ? markup.length() : index + 1;
  }

  private static void applyEntity(String entity, SpannableStringBuilder spannedText) {
    switch (entity) {
      case ENTITY_LESS_THAN:
        spannedText.append('<');
        break;
      case ENTITY_GREATER_THAN:
        spannedText.append('>');
        break;
      case ENTITY_NON_BREAK_SPACE:
        spannedText.append(' ');
        break;
      case ENTITY_AMPERSAND:
        spannedText.append('&');
        break;
      default:
        Log.w(TAG, "ignoring unsupported entity: '&" + entity + ";'");
        break;
    }
  }

  private static boolean isSupportedTag(String tagName) {
    switch (tagName) {
      case TAG_BOLD:
      case TAG_CLASS:
      case TAG_ITALIC:
      case TAG_LANG:
      case TAG_UNDERLINE:
      case TAG_VOICE:
        return true;
      default:
        return false;
    }
  }

  private static void applySpansForTag(
      @Nullable String cueId,
      StartTag startTag,
      SpannableStringBuilder text,
      List<WebvttCssStyle> styles,
      List<StyleMatch> scratchStyleMatches) {
    int start = startTag.position;
    int end = text.length();
    switch(startTag.name) {
      case TAG_BOLD:
        text.setSpan(new StyleSpan(STYLE_BOLD), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case TAG_ITALIC:
        text.setSpan(new StyleSpan(STYLE_ITALIC), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case TAG_UNDERLINE:
        text.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case TAG_CLASS:
      case TAG_LANG:
      case TAG_VOICE:
      case "": // Case of the "whole cue" virtual tag.
        break;
      default:
        return;
    }
    scratchStyleMatches.clear();
    getApplicableStyles(styles, cueId, startTag, scratchStyleMatches);
    int styleMatchesCount = scratchStyleMatches.size();
    for (int i = 0; i < styleMatchesCount; i++) {
      applyStyleToText(text, scratchStyleMatches.get(i).style, start, end);
    }
  }

  private static void applyStyleToText(SpannableStringBuilder spannedText, WebvttCssStyle style,
      int start, int end) {
    if (style == null) {
      return;
    }
    if (style.getStyle() != WebvttCssStyle.UNSPECIFIED) {
      spannedText.setSpan(new StyleSpan(style.getStyle()), start, end,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (style.isLinethrough()) {
      spannedText.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (style.isUnderline()) {
      spannedText.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (style.hasFontColor()) {
      spannedText.setSpan(new ForegroundColorSpan(style.getFontColor()), start, end,
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (style.hasBackgroundColor()) {
      spannedText.setSpan(new BackgroundColorSpan(style.getBackgroundColor()), start, end,
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (style.getFontFamily() != null) {
      spannedText.setSpan(new TypefaceSpan(style.getFontFamily()), start, end,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    Layout.Alignment textAlign = style.getTextAlign();
    if (textAlign != null) {
      spannedText.setSpan(
          new AlignmentSpan.Standard(textAlign), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    switch (style.getFontSizeUnit()) {
      case WebvttCssStyle.FONT_SIZE_UNIT_PIXEL:
        spannedText.setSpan(new AbsoluteSizeSpan((int) style.getFontSize(), true), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case WebvttCssStyle.FONT_SIZE_UNIT_EM:
        spannedText.setSpan(new RelativeSizeSpan(style.getFontSize()), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case WebvttCssStyle.FONT_SIZE_UNIT_PERCENT:
        spannedText.setSpan(new RelativeSizeSpan(style.getFontSize() / 100), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        break;
      case WebvttCssStyle.UNSPECIFIED:
        // Do nothing.
        break;
    }
  }

  /**
   * Returns the tag name for the given tag contents.
   *
   * @param tagExpression Characters between &amp;lt: and &amp;gt; of a start or end tag.
   * @return The name of tag.
   */
  private static String getTagName(String tagExpression) {
    tagExpression = tagExpression.trim();
    Assertions.checkArgument(!tagExpression.isEmpty());
    return Util.splitAtFirst(tagExpression, "[ \\.]")[0];
  }

  private static void getApplicableStyles(
      List<WebvttCssStyle> declaredStyles,
      @Nullable String id,
      StartTag tag,
      List<StyleMatch> output) {
    int styleCount = declaredStyles.size();
    for (int i = 0; i < styleCount; i++) {
      WebvttCssStyle style = declaredStyles.get(i);
      int score = style.getSpecificityScore(id, tag.name, tag.classes, tag.voice);
      if (score > 0) {
        output.add(new StyleMatch(score, style));
      }
    }
    Collections.sort(output);
  }

  private static final class WebvttCueInfoBuilder {

    public long startTimeUs;
    public long endTimeUs;
    public @MonotonicNonNull CharSequence text;
    @TextAlignment public int textAlignment;
    public float line;
    // Equivalent to WebVTT's snap-to-lines flag:
    // https://www.w3.org/TR/webvtt1/#webvtt-cue-snap-to-lines-flag
    @Cue.LineType public int lineType;
    @Cue.AnchorType public int lineAnchor;
    public float position;
    @Cue.AnchorType public int positionAnchor;
    public float size;
    @Cue.VerticalType public int verticalType;

    public WebvttCueInfoBuilder() {
      startTimeUs = 0;
      endTimeUs = 0;
      // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-text-alignment
      textAlignment = TEXT_ALIGNMENT_CENTER;
      line = Cue.DIMEN_UNSET;
      // Defaults to NUMBER (true): https://www.w3.org/TR/webvtt1/#webvtt-cue-snap-to-lines-flag
      lineType = Cue.LINE_TYPE_NUMBER;
      // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-line-alignment
      lineAnchor = Cue.ANCHOR_TYPE_START;
      position = Cue.DIMEN_UNSET;
      positionAnchor = Cue.TYPE_UNSET;
      // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-size
      size = 1.0f;
      verticalType = Cue.TYPE_UNSET;
    }

    public WebvttCueInfo build() {
      return new WebvttCueInfo(toCueBuilder().build(), startTimeUs, endTimeUs);
    }

    public Cue.Builder toCueBuilder() {
      float position =
          this.position != Cue.DIMEN_UNSET ? this.position : derivePosition(textAlignment);
      @Cue.AnchorType
      int positionAnchor =
          this.positionAnchor != Cue.TYPE_UNSET
              ? this.positionAnchor
              : derivePositionAnchor(textAlignment);
      Cue.Builder cueBuilder =
          new Cue.Builder()
              .setTextAlignment(convertTextAlignment(textAlignment))
              .setLine(computeLine(line, lineType), lineType)
              .setLineAnchor(lineAnchor)
              .setPosition(position)
              .setPositionAnchor(positionAnchor)
              .setSize(Math.min(size, deriveMaxSize(positionAnchor, position)))
              .setVerticalType(verticalType);

      if (text != null) {
        cueBuilder.setText(text);
      }

      return cueBuilder;
    }

    // https://www.w3.org/TR/webvtt1/#webvtt-cue-line
    private static float computeLine(float line, @Cue.LineType int lineType) {
      if (line != Cue.DIMEN_UNSET
          && lineType == Cue.LINE_TYPE_FRACTION
          && (line < 0.0f || line > 1.0f)) {
        return 1.0f; // Step 1
      } else if (line != Cue.DIMEN_UNSET) {
        // Step 2: Do nothing, line is already correct.
        return line;
      } else if (lineType == Cue.LINE_TYPE_FRACTION) {
        return 1.0f; // Step 3
      } else {
        // Steps 4 - 10 (stacking multiple simultaneous cues) are handled by
        // WebvttSubtitle.getCues(long) and WebvttSubtitle.isNormal(Cue).
        return Cue.DIMEN_UNSET;
      }
    }

    // https://www.w3.org/TR/webvtt1/#webvtt-cue-position
    private static float derivePosition(@TextAlignment int textAlignment) {
      switch (textAlignment) {
        case TEXT_ALIGNMENT_LEFT:
          return 0.0f;
        case TEXT_ALIGNMENT_RIGHT:
          return 1.0f;
        case TEXT_ALIGNMENT_START:
        case TEXT_ALIGNMENT_CENTER:
        case TEXT_ALIGNMENT_END:
        default:
          return DEFAULT_POSITION;
      }
    }

    // https://www.w3.org/TR/webvtt1/#webvtt-cue-position-alignment
    @Cue.AnchorType
    private static int derivePositionAnchor(@TextAlignment int textAlignment) {
      switch (textAlignment) {
        case TEXT_ALIGNMENT_LEFT:
        case TEXT_ALIGNMENT_START:
          return Cue.ANCHOR_TYPE_START;
        case TEXT_ALIGNMENT_RIGHT:
        case TEXT_ALIGNMENT_END:
          return Cue.ANCHOR_TYPE_END;
        case TEXT_ALIGNMENT_CENTER:
        default:
          return Cue.ANCHOR_TYPE_MIDDLE;
      }
    }

    @Nullable
    private static Layout.Alignment convertTextAlignment(@TextAlignment int textAlignment) {
      switch (textAlignment) {
        case TEXT_ALIGNMENT_START:
        case TEXT_ALIGNMENT_LEFT:
          return Layout.Alignment.ALIGN_NORMAL;
        case TEXT_ALIGNMENT_CENTER:
          return Layout.Alignment.ALIGN_CENTER;
        case TEXT_ALIGNMENT_END:
        case TEXT_ALIGNMENT_RIGHT:
          return Layout.Alignment.ALIGN_OPPOSITE;
        default:
          Log.w(TAG, "Unknown textAlignment: " + textAlignment);
          return null;
      }
    }

    // Step 2 here: https://www.w3.org/TR/webvtt1/#processing-cue-settings
    private static float deriveMaxSize(@Cue.AnchorType int positionAnchor, float position) {
      switch (positionAnchor) {
        case Cue.ANCHOR_TYPE_START:
          return 1.0f - position;
        case Cue.ANCHOR_TYPE_END:
          return position;
        case Cue.ANCHOR_TYPE_MIDDLE:
          if (position <= 0.5f) {
            return position * 2;
          } else {
            return (1.0f - position) * 2;
          }
        case Cue.TYPE_UNSET:
        default:
          throw new IllegalStateException(String.valueOf(positionAnchor));
      }
    }
  }

  private static final class StyleMatch implements Comparable<StyleMatch> {

    public final int score;
    public final WebvttCssStyle style;

    public StyleMatch(int score, WebvttCssStyle style) {
      this.score = score;
      this.style = style;
    }

    @Override
    public int compareTo(@NonNull StyleMatch another) {
      return this.score - another.score;
    }

  }

  private static final class StartTag {

    private static final String[] NO_CLASSES = new String[0];

    public final String name;
    public final int position;
    public final String voice;
    public final String[] classes;

    private StartTag(String name, int position, String voice, String[] classes) {
      this.position = position;
      this.name = name;
      this.voice = voice;
      this.classes = classes;
    }

    public static StartTag buildStartTag(String fullTagExpression, int position) {
      fullTagExpression = fullTagExpression.trim();
      Assertions.checkArgument(!fullTagExpression.isEmpty());
      int voiceStartIndex = fullTagExpression.indexOf(" ");
      String voice;
      if (voiceStartIndex == -1) {
        voice = "";
      } else {
        voice = fullTagExpression.substring(voiceStartIndex).trim();
        fullTagExpression = fullTagExpression.substring(0, voiceStartIndex);
      }
      String[] nameAndClasses = Util.split(fullTagExpression, "\\.");
      String name = nameAndClasses[0];
      String[] classes;
      if (nameAndClasses.length > 1) {
        classes = Util.nullSafeArrayCopyOfRange(nameAndClasses, 1, nameAndClasses.length);
      } else {
        classes = NO_CLASSES;
      }
      return new StartTag(name, position, voice, classes);
    }

    public static StartTag buildWholeCueVirtualTag() {
      return new StartTag("", 0, "", new String[0]);
    }

  }
}
