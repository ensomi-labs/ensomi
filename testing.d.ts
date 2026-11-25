// Type definitions for ensomi/testing

import type {
  AnalysisDraft,
  AnalyzeRequest,
  AudioBinding,
  BeatInput,
  BoundaryInput,
  EvidenceRef,
  MatchSoundsRequest,
  OfferInput,
  Provider,
  ProviderManifest,
  RecomputeRequest,
  RecomputeResult,
  SegmentInput,
  SnapCandidate,
  SoundInput,
  SoundOptionCandidate,
  SpanInput,
  SuggestSegmentationRequest,
  SuggestSoundOptionsRequest,
  SuggestSnapsRequest,
} from "./index.d.ts";

export interface SyntheticDraftOptions {
  durationUs?: number;
  durationSec?: number;
  bpm?: number;
  jitterUs?: number;
  soundCount?: number;
  segmentCount?: number;
  seed?: number;
  startUs?: number;
}

export interface SyntheticDraft {
  segments: SegmentInput[];
  boundaries: BoundaryInput[];
  spans: SpanInput[];
  beats: BeatInput[];
  sounds: SoundInput[];
}

export interface ScriptedMockProviderOptions {
  id?: string;
  version?: string;
  modelRevision?: string;
  draft?: AnalysisDraft;
  defaultScenario?:
    | "local_rewrite"
    | "triplet_vs_reset"
    | "propagation"
    | "cross_region"
    | "protected_keep_conflict"
    | "challenge_verified"
    | "slow_cancellable";
  delayMs?: number;
  analyzeDelayMs?: number;
  snapDelayMs?: number;
  ignoreAbort?: boolean;
  script?: Array<
    | RecomputeResult
    | ((ctx: {
        request: RecomputeRequest;
        signal?: AbortSignal;
        callIdx: number;
      }) => RecomputeResult | Promise<RecomputeResult>)
  >;
  snapScript?: Array<
    | SnapCandidate[]
    | ((ctx: {
        request: SuggestSnapsRequest;
        signal?: AbortSignal;
        callIdx: number;
      }) => SnapCandidate[] | Promise<SnapCandidate[]>)
  >;
  soundOptionScript?: Array<
    | SoundOptionCandidate[]
    | ((ctx: {
        request: SuggestSoundOptionsRequest;
        signal?: AbortSignal;
      }) => SoundOptionCandidate[] | Promise<SoundOptionCandidate[]>)
  >;
  matchScript?: Array<
    | OfferInput[]
    | RecomputeResult
    | ((ctx: {
        request: MatchSoundsRequest;
        signal?: AbortSignal;
      }) => OfferInput[] | RecomputeResult | Promise<OfferInput[] | RecomputeResult>)
  >;
  segmentationScript?: Array<
    | OfferInput[]
    | RecomputeResult
    | ((ctx: {
        request: SuggestSegmentationRequest;
        signal?: AbortSignal;
      }) => OfferInput[] | RecomputeResult | Promise<OfferInput[] | RecomputeResult>)
  >;
}

export declare function createDeterministicPrng(seed?: number): () => number;
export declare function generateSyntheticDraft(options?: SyntheticDraftOptions): SyntheticDraft;

export declare class ScriptedMockProvider implements Provider {
  cancelledRequestIds: Set<string>;
  prepareCalls: Array<{ audioId: string }>;
  analyzeCalls: Array<{ requestId: string; generation: number; aborted: boolean }>;
  suggestSnapsCalls: Array<{
    requestId: string;
    generation: number;
    atUs: number;
    windowUs: number;
    aborted: boolean;
  }>;
  suggestSoundOptionsCalls: Array<{ requestId: string; generation: number; aborted: boolean }>;
  matchSoundsCalls: Array<{
    requestId: string;
    generation: number;
    matchMode: string;
    aborted: boolean;
  }>;
  suggestSegmentationCalls: Array<{ requestId: string; generation: number; aborted: boolean }>;
  recomputeCalls: Array<{
    requestId: string;
    generation: number;
    reason: string;
    aborted: boolean;
  }>;

  constructor(options?: ScriptedMockProviderOptions);
  manifest(): ProviderManifest;
  cancel(requestId: string): void;
  prepare(audio: AudioBinding, signal?: AbortSignal): Promise<EvidenceRef>;
  analyze(request: AnalyzeRequest, signal?: AbortSignal): Promise<AnalysisDraft>;
  suggestSnaps(request: SuggestSnapsRequest, signal?: AbortSignal): Promise<SnapCandidate[]>;
  suggestSoundOptions(
    request: SuggestSoundOptionsRequest,
    signal?: AbortSignal,
  ): Promise<SoundOptionCandidate[]>;
  matchSounds(
    request: MatchSoundsRequest,
    signal?: AbortSignal,
  ): Promise<OfferInput[] | RecomputeResult>;
  suggestSegmentation(
    request: SuggestSegmentationRequest,
    signal?: AbortSignal,
  ): Promise<OfferInput[] | RecomputeResult>;
  recompute(request: RecomputeRequest, signal?: AbortSignal): Promise<RecomputeResult>;
}
