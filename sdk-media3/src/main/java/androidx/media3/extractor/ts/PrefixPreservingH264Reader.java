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
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR;
import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.container.NalUnitUtil.SpsData;
import androidx.media3.container.ParsableNalUnitBitArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Media3 1.11.0 H264Reader with access-unit prefix preservation.
 *
 * <p>Version-pinned adaptation of
 * https://github.com/androidx/media/blob/1.11.0/libraries/extractor/src/main/java/androidx/media3/extractor/ts/H264Reader.java.
 * The maintained slice parser is unchanged. SampleReader retains prefix NALs with the following
 * picture instead of excluding the first prefix and assigning later recovery SEI to the prior sample.
 * This package deliberately permits reuse of Media3's package-private NalUnitTargetBuffer.
 */
@UnstableApi
public final class PrefixPreservingH264Reader implements ElementaryStreamReader {
  private final SeiReader seiReader;
  private final boolean allowNonIdrKeyframes;
  private final boolean detectAccessUnits;
  private final String containerMimeType;
  private final NalUnitTargetBuffer sps;
  private final NalUnitTargetBuffer pps;
  private final NalUnitTargetBuffer sei;
  private long totalBytesWritten;
  private final boolean[] prefixFlags;
  private final byte[] previousTail = new byte[4];
  private int previousTailLength;
  private String formatId;
  private TrackOutput output;
  private SampleReader sampleReader;
  private boolean hasOutputFormat;
  private long pesTimeUs;
  private boolean randomAccessIndicator;
  private final ParsableByteArray seiWrapper;

  public PrefixPreservingH264Reader(
      SeiReader seiReader, boolean allowNonIdrKeyframes, boolean detectAccessUnits,
      String containerMimeType) {
    this.seiReader = seiReader;
    this.allowNonIdrKeyframes = allowNonIdrKeyframes;
    this.detectAccessUnits = detectAccessUnits;
    this.containerMimeType = containerMimeType;
    prefixFlags = new boolean[3];
    sps = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SPS, 128);
    pps = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_PPS, 128);
    sei = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SEI, 128);
    pesTimeUs = C.TIME_UNSET;
    seiWrapper = new ParsableByteArray();
  }

  @Override
  public void seek() {
    totalBytesWritten = 0;
    previousTailLength = 0;
    randomAccessIndicator = false;
    pesTimeUs = C.TIME_UNSET;
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    sps.reset();
    pps.reset();
    sei.reset();
    seiReader.clear();
    if (sampleReader != null) sampleReader.reset();
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    sampleReader = new SampleReader(output, allowNonIdrKeyframes, detectAccessUnits);
    seiReader.createTracks(extractorOutput, idGenerator);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.pesTimeUs = pesTimeUs;
    randomAccessIndicator |= (flags & FLAG_RANDOM_ACCESS_INDICATOR) != 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    assertTracksCreated();
    int offset = data.getPosition();
    int start = offset;
    int limit = data.limit();
    byte[] dataArray = data.getData();
    totalBytesWritten += data.bytesLeft();
    output.sampleData(data, data.bytesLeft());
    while (true) {
      int nalUnitOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);
      if (nalUnitOffset == limit) {
        nalUnitData(dataArray, offset, limit);
        int copied = Math.min(4, limit - start);
        int retained = Math.min(previousTailLength, 4 - copied);
        System.arraycopy(previousTail, previousTailLength - retained, previousTail, 0, retained);
        System.arraycopy(dataArray, limit - copied, previousTail, retained, copied);
        previousTailLength = retained + copied;
        return;
      }
      int nalUnitType = NalUnitUtil.getNalUnitType(dataArray, nalUnitOffset);
      int prefixSize = 3;
      int previousIndex = previousTailLength + nalUnitOffset - start - 1;
      // Preserve the same four-byte prefix boundary even when its bytes span consume calls.
      boolean precedingZero = nalUnitOffset > start
          ? dataArray[nalUnitOffset - 1] == 0
          : previousIndex >= 0 && previousTail[previousIndex] == 0;
      if (precedingZero) {
        nalUnitOffset--;
        prefixSize = 4;
      }
      int lengthToNalUnit = nalUnitOffset - offset;
      if (lengthToNalUnit > 0) nalUnitData(dataArray, offset, nalUnitOffset);
      int bytesWrittenPastPosition = limit - nalUnitOffset;
      long absolutePosition = totalBytesWritten - bytesWrittenPastPosition;
      endNalUnit(absolutePosition, bytesWrittenPastPosition,
          lengthToNalUnit < 0 ? -lengthToNalUnit : 0, pesTimeUs);
      startNalUnit(absolutePosition, nalUnitType, pesTimeUs);
      offset = nalUnitOffset + prefixSize;
    }
  }

  @Override
  public void endOfInputReached() {
    assertTracksCreated();
    seiReader.flush();
    endNalUnit(totalBytesWritten, 0, 0, pesTimeUs);
    sampleReader.endOfInput(totalBytesWritten, hasOutputFormat);
  }

  private void startNalUnit(long position, int nalUnitType, long pesTimeUs) {
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.startNalUnit(nalUnitType);
      pps.startNalUnit(nalUnitType);
    }
    sei.startNalUnit(nalUnitType);
    sampleReader.startNalUnit(position, nalUnitType, pesTimeUs, randomAccessIndicator);
  }

  private void nalUnitData(byte[] dataArray, int offset, int limit) {
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.appendToNalUnit(dataArray, offset, limit);
      pps.appendToNalUnit(dataArray, offset, limit);
    }
    sei.appendToNalUnit(dataArray, offset, limit);
    sampleReader.appendToNalUnit(dataArray, offset, limit);
  }

  private void endNalUnit(long position, int offset, int discardPadding, long pesTimeUs) {
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.endNalUnit(discardPadding);
      pps.endNalUnit(discardPadding);
      if (!hasOutputFormat) {
        if (sps.isCompleted() && pps.isCompleted()) {
          List<byte[]> initializationData = new ArrayList<>();
          initializationData.add(Arrays.copyOf(sps.nalData, sps.nalLength));
          initializationData.add(Arrays.copyOf(pps.nalData, pps.nalLength));
          SpsData spsData = NalUnitUtil.parseSpsNalUnit(sps.nalData, 3, sps.nalLength);
          NalUnitUtil.PpsData ppsData = NalUnitUtil.parsePpsNalUnit(pps.nalData, 3, pps.nalLength);
          String codecs = CodecSpecificDataUtil.buildAvcCodecString(
              spsData.profileIdc, spsData.constraintsFlagsAndReservedZero2Bits, spsData.levelIdc);
          output.format(new Format.Builder().setId(formatId).setContainerMimeType(containerMimeType)
              .setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs(codecs)
              .setWidth(spsData.width).setHeight(spsData.height)
              .setColorInfo(new ColorInfo.Builder().setColorSpace(spsData.colorSpace)
                  .setColorRange(spsData.colorRange).setColorTransfer(spsData.colorTransfer)
                  .setLumaBitdepth(spsData.bitDepthLumaMinus8 + 8)
                  .setChromaBitdepth(spsData.bitDepthChromaMinus8 + 8).build())
              .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
              .setInitializationData(initializationData)
              .setMaxNumReorderSamples(spsData.maxNumReorderFrames).build());
          hasOutputFormat = true;
          seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames);
          sampleReader.putSps(spsData);
          sampleReader.putPps(ppsData);
          sps.reset();
          pps.reset();
        }
      } else if (sps.isCompleted()) {
        SpsData spsData = NalUnitUtil.parseSpsNalUnit(sps.nalData, 3, sps.nalLength);
        seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames);
        sampleReader.putSps(spsData);
        sps.reset();
      } else if (pps.isCompleted()) {
        sampleReader.putPps(NalUnitUtil.parsePpsNalUnit(pps.nalData, 3, pps.nalLength));
        pps.reset();
      }
    }
    if (sei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(sei.nalData, sei.nalLength);
      seiWrapper.reset(sei.nalData, unescapedLength);
      seiWrapper.setPosition(4);
      seiReader.consume(pesTimeUs, seiWrapper);
    }
    if (sampleReader.endNalUnit(position, offset, hasOutputFormat)) randomAccessIndicator = false;
  }

  private void assertTracksCreated() {
    checkNotNull(output);
    Util.castNonNull(sampleReader);
  }

  private static final class SampleReader {
    private final TrackOutput output;
    private final boolean allowNonIdrKeyframes;
    private final boolean detectAccessUnits;
    private final SparseArray<SpsData> sps = new SparseArray<>();
    private final SparseArray<NalUnitUtil.PpsData> pps = new SparseArray<>();
    private final ParsableNalUnitBitArray bitArray;
    private byte[] buffer = new byte[128];
    private int bufferLength;
    private int nalUnitType;
    private long nalUnitStartPosition;
    private boolean isFilling;
    private long nalUnitTimeUs;
    private SliceHeaderData previousSliceHeader = new SliceHeaderData();
    private SliceHeaderData sliceHeader = new SliceHeaderData();
    private boolean readingSample;
    private long samplePosition;
    private long sampleTimeUs;
    private boolean sampleIsKeyframe;
    private boolean randomAccessIndicator;
    private long prefixPosition;
    private boolean sampleHasVcl;

    public SampleReader(TrackOutput output, boolean allowNonIdrKeyframes, boolean detectAccessUnits) {
      this.output = output;
      this.allowNonIdrKeyframes = allowNonIdrKeyframes;
      this.detectAccessUnits = detectAccessUnits;
      bitArray = new ParsableNalUnitBitArray(buffer, 0, 0);
      reset();
    }

    public boolean needsSpsPps() { return detectAccessUnits; }
    public void putSps(SpsData data) { sps.append(data.seqParameterSetId, data); }
    public void putPps(NalUnitUtil.PpsData data) { pps.append(data.picParameterSetId, data); }

    public void reset() {
      nalUnitType = -1;
      isFilling = false;
      readingSample = false;
      sampleHasVcl = false;
      prefixPosition = -1;
      sliceHeader.clear();
    }

    public void startNalUnit(long position, int type, long pesTimeUs, boolean randomAccessIndicator) {
      nalUnitType = type;
      nalUnitTimeUs = pesTimeUs;
      nalUnitStartPosition = position;
      this.randomAccessIndicator = randomAccessIndicator;
      // AVC 7.4.1.2.3: these NALs precede the next picture, not the preceding VCL sample.
      if ((!readingSample || sampleHasVcl) && prefixPosition == -1
          && ((type >= 6 && type <= 9) || (type >= 14 && type <= 18))) {
        prefixPosition = position;
      }
      if ((allowNonIdrKeyframes && type == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR)
          || (detectAccessUnits && (type == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
              || type == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR
              || type == NalUnitUtil.H264_NAL_UNIT_TYPE_PARTITION_A))) {
        SliceHeaderData newSliceHeader = previousSliceHeader;
        previousSliceHeader = sliceHeader;
        sliceHeader = newSliceHeader;
        sliceHeader.clear();
        bufferLength = 0;
        isFilling = true;
      }
    }

    public void appendToNalUnit(byte[] data, int offset, int limit) {
      if (!isFilling) return;
      int readLength = limit - offset;
      if (buffer.length < bufferLength + readLength) {
        buffer = Arrays.copyOf(buffer, (bufferLength + readLength) * 2);
      }
      System.arraycopy(data, offset, buffer, bufferLength, readLength);
      bufferLength += readLength;
      bitArray.reset(buffer, 0, bufferLength);
      if (!bitArray.canReadBits(8)) return;
      bitArray.skipBit();
      int nalRefIdc = bitArray.readBits(2);
      bitArray.skipBits(5);
      if (!bitArray.canReadExpGolombCodedNum()) return;
      bitArray.readUnsignedExpGolombCodedInt();
      if (!bitArray.canReadExpGolombCodedNum()) return;
      int sliceType = bitArray.readUnsignedExpGolombCodedInt();
      if (!detectAccessUnits) {
        isFilling = false;
        sliceHeader.setSliceType(sliceType);
        return;
      }
      if (!bitArray.canReadExpGolombCodedNum()) return;
      int picParameterSetId = bitArray.readUnsignedExpGolombCodedInt();
      if (pps.indexOfKey(picParameterSetId) < 0) {
        isFilling = false;
        return;
      }
      NalUnitUtil.PpsData ppsData = pps.get(picParameterSetId);
      SpsData spsData = sps.get(ppsData.seqParameterSetId);
      if (spsData.separateColorPlaneFlag) {
        if (!bitArray.canReadBits(2)) return;
        bitArray.skipBits(2);
      }
      if (!bitArray.canReadBits(spsData.frameNumLength)) return;
      boolean fieldPicFlag = false;
      boolean bottomFieldFlagPresent = false;
      boolean bottomFieldFlag = false;
      int frameNum = bitArray.readBits(spsData.frameNumLength);
      if (!spsData.frameMbsOnlyFlag) {
        if (!bitArray.canReadBits(1)) return;
        fieldPicFlag = bitArray.readBit();
        if (fieldPicFlag) {
          if (!bitArray.canReadBits(1)) return;
          bottomFieldFlag = bitArray.readBit();
          bottomFieldFlagPresent = true;
        }
      }
      boolean idrPicFlag = nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR;
      int idrPicId = 0;
      if (idrPicFlag) {
        if (!bitArray.canReadExpGolombCodedNum()) return;
        idrPicId = bitArray.readUnsignedExpGolombCodedInt();
      }
      int picOrderCntLsb = 0;
      int deltaPicOrderCntBottom = 0;
      int deltaPicOrderCnt0 = 0;
      int deltaPicOrderCnt1 = 0;
      if (spsData.picOrderCountType == 0) {
        if (!bitArray.canReadBits(spsData.picOrderCntLsbLength)) return;
        picOrderCntLsb = bitArray.readBits(spsData.picOrderCntLsbLength);
        if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
          if (!bitArray.canReadExpGolombCodedNum()) return;
          deltaPicOrderCntBottom = bitArray.readSignedExpGolombCodedInt();
        }
      } else if (spsData.picOrderCountType == 1 && !spsData.deltaPicOrderAlwaysZeroFlag) {
        if (!bitArray.canReadExpGolombCodedNum()) return;
        deltaPicOrderCnt0 = bitArray.readSignedExpGolombCodedInt();
        if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
          if (!bitArray.canReadExpGolombCodedNum()) return;
          deltaPicOrderCnt1 = bitArray.readSignedExpGolombCodedInt();
        }
      }
      sliceHeader.setAll(spsData, nalRefIdc, sliceType, frameNum, picParameterSetId, fieldPicFlag,
          bottomFieldFlagPresent, bottomFieldFlag, idrPicFlag, idrPicId, picOrderCntLsb,
          deltaPicOrderCntBottom, deltaPicOrderCnt0, deltaPicOrderCnt1);
      isFilling = false;
    }

    public boolean endNalUnit(long position, int offset, boolean hasOutputFormat) {
      boolean vcl = nalUnitType >= 1 && nalUnitType <= 5;
      boolean newPicture = detectAccessUnits
          && sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader);
      if (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_AUD
          || (vcl && (newPicture || prefixPosition != -1) && (!readingSample || sampleHasVcl))) {
        long boundary = prefixPosition == -1 ? nalUnitStartPosition : prefixPosition;
        if (hasOutputFormat && readingSample && sampleHasVcl) {
          outputSample(boundary, offset + (int) (position - boundary));
        }
        samplePosition = boundary;
        sampleTimeUs = nalUnitTimeUs;
        sampleIsKeyframe = false;
        sampleHasVcl = false;
        readingSample = true;
        prefixPosition = -1;
      }
      if (vcl && readingSample) sampleHasVcl = true;
      boolean treatIFrameAsKeyframe = allowNonIdrKeyframes ? sliceHeader.isISlice() : randomAccessIndicator;
      sampleIsKeyframe |= nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
          || (treatIFrameAsKeyframe && nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR);
      nalUnitType = NalUnitUtil.H264_NAL_UNIT_TYPE_UNSPECIFIED;
      return sampleIsKeyframe;
    }

    public void endOfInput(long position, boolean hasOutputFormat) {
      if (hasOutputFormat && readingSample && sampleHasVcl) {
        long end = prefixPosition == -1 ? position : prefixPosition;
        outputSample(end, (int) (position - end));
      }
      readingSample = false;
      sampleHasVcl = false;
      // Retain a trailing prefix for a later continuation, but never emit it without a picture.
      sliceHeader.clear();
    }

    private void outputSample(long end, int offset) {
      if (sampleTimeUs == C.TIME_UNSET || end == samplePosition) return;
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      output.sampleMetadata(sampleTimeUs, flags, (int) (end - samplePosition), offset, null);
    }

    private static final class SliceHeaderData {
      private boolean isComplete;
      private boolean hasSliceType;
      private SpsData spsData;
      private int nalRefIdc;
      private int sliceType;
      private int frameNum;
      private int picParameterSetId;
      private boolean fieldPicFlag;
      private boolean bottomFieldFlagPresent;
      private boolean bottomFieldFlag;
      private boolean idrPicFlag;
      private int idrPicId;
      private int picOrderCntLsb;
      private int deltaPicOrderCntBottom;
      private int deltaPicOrderCnt0;
      private int deltaPicOrderCnt1;

      public void clear() { hasSliceType = false; isComplete = false; }
      public void setSliceType(int sliceType) { this.sliceType = sliceType; hasSliceType = true; }

      public void setAll(SpsData spsData, int nalRefIdc, int sliceType, int frameNum,
          int picParameterSetId, boolean fieldPicFlag, boolean bottomFieldFlagPresent,
          boolean bottomFieldFlag, boolean idrPicFlag, int idrPicId, int picOrderCntLsb,
          int deltaPicOrderCntBottom, int deltaPicOrderCnt0, int deltaPicOrderCnt1) {
        this.spsData = spsData;
        this.nalRefIdc = nalRefIdc;
        this.sliceType = sliceType;
        this.frameNum = frameNum;
        this.picParameterSetId = picParameterSetId;
        this.fieldPicFlag = fieldPicFlag;
        this.bottomFieldFlagPresent = bottomFieldFlagPresent;
        this.bottomFieldFlag = bottomFieldFlag;
        this.idrPicFlag = idrPicFlag;
        this.idrPicId = idrPicId;
        this.picOrderCntLsb = picOrderCntLsb;
        this.deltaPicOrderCntBottom = deltaPicOrderCntBottom;
        this.deltaPicOrderCnt0 = deltaPicOrderCnt0;
        this.deltaPicOrderCnt1 = deltaPicOrderCnt1;
        isComplete = true;
        hasSliceType = true;
      }

      public boolean isISlice() { return hasSliceType && (sliceType == 7 || sliceType == 2); }

      private boolean isFirstVclNalUnitOfPicture(SliceHeaderData other) {
        if (!isComplete) return false;
        if (!other.isComplete) return true;
        SpsData spsData = checkNotNull(this.spsData);
        SpsData otherSpsData = checkNotNull(other.spsData);
        return frameNum != other.frameNum || picParameterSetId != other.picParameterSetId
            || fieldPicFlag != other.fieldPicFlag
            || (bottomFieldFlagPresent && other.bottomFieldFlagPresent && bottomFieldFlag != other.bottomFieldFlag)
            || (nalRefIdc != other.nalRefIdc && (nalRefIdc == 0 || other.nalRefIdc == 0))
            || (spsData.picOrderCountType == 0 && otherSpsData.picOrderCountType == 0
                && (picOrderCntLsb != other.picOrderCntLsb || deltaPicOrderCntBottom != other.deltaPicOrderCntBottom))
            || (spsData.picOrderCountType == 1 && otherSpsData.picOrderCountType == 1
                && (deltaPicOrderCnt0 != other.deltaPicOrderCnt0 || deltaPicOrderCnt1 != other.deltaPicOrderCnt1))
            || idrPicFlag != other.idrPicFlag || (idrPicFlag && idrPicId != other.idrPicId);
      }
    }
  }
}
