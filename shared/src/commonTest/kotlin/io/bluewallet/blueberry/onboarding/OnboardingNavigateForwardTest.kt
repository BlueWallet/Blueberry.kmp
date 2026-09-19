package io.bluewallet.blueberry.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingNavigateForwardTest {
    @Test
    fun choose_to_import_or_create_is_forward() {
        assertTrue(onboardingNavigatesForward(OnboardingStep.Choose, OnboardingStep.Import))
        assertTrue(onboardingNavigatesForward(OnboardingStep.Choose, OnboardingStep.Create))
        assertTrue(onboardingNavigatesForward(OnboardingStep.Import, OnboardingStep.Year))
    }

    @Test
    fun back_to_choose_is_not_forward() {
        assertFalse(onboardingNavigatesForward(OnboardingStep.Import, OnboardingStep.Choose))
        assertFalse(onboardingNavigatesForward(OnboardingStep.Create, OnboardingStep.Choose))
        assertFalse(onboardingNavigatesForward(OnboardingStep.Choose, OnboardingStep.Choose))
    }
}
