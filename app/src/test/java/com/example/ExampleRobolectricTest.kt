package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("NEXUS TRADING AI", appName)
  }

  @Test
  fun `generated firebase default_web_client_id resource is present`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val webClientId = context.getString(R.string.default_web_client_id)
    assertTrue(
      "Expected generated default_web_client_id from google-services.json",
      webClientId.endsWith(".apps.googleusercontent.com")
    )
  }
}
