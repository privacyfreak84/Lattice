package org.lattice.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.lattice.moderation.HybridTextModerator
import org.lattice.moderation.ImageModerator
import org.lattice.moderation.ImageScreeningService
import org.lattice.moderation.LexicalTextFilter
import org.lattice.moderation.MlTextModerator
import org.lattice.moderation.NsfwImageModerator
import org.lattice.moderation.ScopedTextModerator
import org.lattice.moderation.WordList

/**
 * On-device content moderation. Everything runs locally against bundled assets/models — the app has no
 * network. Text moderation is scoped by conversation ([ScopedTextModerator]): the public Nearby
 * broadcast room runs the deterministic lexical profanity filter first, then the on-device ML toxicity
 * classifier ([MlTextModerator], Detoxify/ALBERT) on anything it clears; private DMs and groups run the
 * toxicity classifier only (profanity is limited to the public room). Each moderator degrades to
 * allow-all if its model/tokenizer assets are missing.
 */
val moderationModule =
    module {
        // Shared so the heavy toxicity model is loaded at most once across both moderation scopes.
        single { MlTextModerator(androidContext()) }
        single {
            ScopedTextModerator(
                // Nearby broadcast room: profanity word-list, then ML toxicity on what it clears.
                room =
                    HybridTextModerator(
                        lexical = LexicalTextFilter(WordList.loadProfanity(androidContext())),
                        ml = get<MlTextModerator>(),
                    ),
                // DMs and groups: toxicity only.
                direct = get<MlTextModerator>(),
            )
        }
        single<ImageModerator> { NsfwImageModerator(androidContext()) }
        // Screens image blobs against the classifier and caches the NSFW verdict by content hash
        // (blobVerdictDao). Extracted from BlobRepository so the data layer no longer invokes the classifier.
        single { ImageScreeningService(get(), get()) }
    }
