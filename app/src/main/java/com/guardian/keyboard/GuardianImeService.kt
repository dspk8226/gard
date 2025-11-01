package com.guardian.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Custom Input Method Service (IME) that provides keyboard functionality
 * with real-time negative word detection using a Bag-of-Words approach.
 */
class GuardianImeService : InputMethodService() {

    companion object {
        private const val TAG = "GuardianIME"
    }

    // Buffer to accumulate typed characters until word boundaries
    private var wordBuffer = StringBuilder()
    
    // Running safety score accumulated during typing session
    private var runningScore = 0
    
    // Default threshold for triggering safety alert
    private var threshold = 5
    
    // Negative Bag-of-Words loaded from JSON
    private var nbow: JSONObject? = null
    
    // Guardian phone number for SMS alerts
    private var guardianNumber: String = ""
    
    // View for the keyboard input
    private var inputView: View? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========== GuardianImeService.onCreate() ==========")
        // Load NBOW early to ensure it's available
        loadNBOW()
        loadConfiguration()
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "========== onCreateInputView called - keyboard view being created ==========")
        // Load configuration and NBOW when keyboard starts
        loadConfiguration()
        loadNBOW()
        
        Log.d(TAG, "Loaded config - Threshold: $threshold, Guardian: $guardianNumber")
        val nbowKeys = nbow?.keys()?.asSequence()?.toList()?.joinToString() ?: "none"
        Log.d(TAG, "NBOW loaded: $nbowKeys")
        
        // Create a simple input view (can be expanded with actual keyboard UI)
        inputView = layoutInflater.inflate(R.layout.input_view, null)
        Log.d(TAG, "Input view created successfully")
        return inputView!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "========== onStartInputView - Keyboard started ==========")
        // Reload configuration each time keyboard becomes active
        loadConfiguration()
        loadNBOW()
        // Check for reset flag from BlockingActivity
        checkResetFlag()
        Log.d(TAG, "Threshold: $threshold, Guardian: $guardianNumber")
        Log.d(TAG, "Current score: $runningScore")
        Log.d(TAG, "NBOW is null: ${nbow == null}")
        if (nbow != null) {
            val nbowKeys = nbow?.keys()?.asSequence()?.toList()?.joinToString() ?: "none"
            Log.d(TAG, "NBOW categories: $nbowKeys")
        }
        
        // Set up text monitoring by wrapping the current input connection
        setupTextMonitoring()
    }
    
    /**
     * Sets up text monitoring
     * Text input is captured through onKeyDown method when keys are pressed
     */
    private fun setupTextMonitoring() {
        // Text monitoring happens through onKeyDown method
        // All keyboard input goes through onKeyDown when Guardian Keyboard is active
    }

    /**
     * Checks for reset flag file and resets score if present
     */
    private fun checkResetFlag() {
        try {
            val resetFile = File(filesDir, "reset_score.flag")
            if (resetFile.exists()) {
                runningScore = 0
                resetFile.delete() // Remove flag after processing
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Loads NBOW from assets/nbow.json file
     */
    private fun loadNBOW() {
        try {
            Log.d(TAG, "Loading NBOW from assets...")
            val inputStream: InputStream = assets.open("nbow.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            nbow = JSONObject(jsonString)
            val nbowKeys = nbow?.keys()?.asSequence()?.toList()?.joinToString() ?: "none"
            Log.d(TAG, "NBOW loaded successfully. Categories: $nbowKeys")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading NBOW from assets", e)
            e.printStackTrace()
            // Try loading from filesDir if assets fails
            try {
                val nbowFile = File(filesDir, "nbow.json")
                if (nbowFile.exists()) {
                    val jsonString = nbowFile.readText()
                    nbow = JSONObject(jsonString)
                    Log.d(TAG, "NBOW loaded from filesDir instead")
                } else {
                    Log.w(TAG, "NBOW file not found in assets or filesDir, using empty NBOW")
                    nbow = JSONObject("{}")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Error loading NBOW from filesDir", e2)
                nbow = JSONObject("{}")
            }
        }
    }

    /**
     * Loads configuration (threshold, guardian number) from internal storage
     */
    private fun loadConfiguration() {
        try {
            val configFile = File(filesDir, "config.json")
            if (configFile.exists()) {
                val jsonString = configFile.readText()
                val config = JSONObject(jsonString)
                threshold = config.optInt("threshold", 5)
                guardianNumber = config.optString("guardianNumber", "")
            } else {
                // Default values
                threshold = 5
                guardianNumber = ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            threshold = 5
            guardianNumber = ""
        }
    }

    /**
     * Override onFinishInput to process any remaining words when input session ends
     */
    override fun onFinishInput() {
        super.onFinishInput()
        // Process any remaining word in buffer
        val word = wordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            Log.d(TAG, "Processing final word: '$word'")
            processWord(word)
            wordBuffer.clear()
        }
    }

    /**
     * Handles key down events to capture typed characters
     * This method is called when keys are pressed on the keyboard
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown called: keyCode=$keyCode, unicodeChar=${event?.unicodeChar}, char=${event?.unicodeChar?.toChar()}")
        val inputConnection = currentInputConnection
        if (inputConnection == null) {
            Log.w(TAG, "No InputConnection available!")
            return super.onKeyDown(keyCode, event)
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                // Word boundary: process accumulated word
                val word = wordBuffer.toString().trim()
                Log.d(TAG, "Processing word: '$word'")
                processWord(word)
                wordBuffer.clear()
                inputConnection.commitText(" ", 1)
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                val word = wordBuffer.toString().trim()
                Log.d(TAG, "Processing word (Enter): '$word'")
                processWord(word)
                wordBuffer.clear()
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DEL -> {
                if (wordBuffer.isNotEmpty()) {
                    wordBuffer.deleteCharAt(wordBuffer.length - 1)
                }
                return super.onKeyDown(keyCode, event)
            }
            else -> {
                // Capture printable characters
                val unicodeChar = event?.unicodeChar ?: 0
                val char = unicodeChar.toChar()
                if (char.isLetterOrDigit() || char == '\'') {
                    wordBuffer.append(char)
                } else if (char.isWhitespace() || 
                           char in listOf('.', ',', '!', '?', ';', ':', '-', '_')) {
                    // Punctuation also finalizes word
                    val word = wordBuffer.toString().trim()
                    Log.d(TAG, "Processing word (punctuation): '$word'")
                    processWord(word)
                    wordBuffer.clear()
                }
                return super.onKeyDown(keyCode, event)
            }
        }
    }

    /**
     * Helper method to handle text committed directly via InputConnection
     * Can be called from keyboard UI when text is committed
     */
    fun onTextCommitted(text: CharSequence) {
        Log.d(TAG, "Text committed: '$text'")
        // Process the committed text character by character
        for (char in text) {
            if (char.isLetterOrDigit() || char == '\'') {
                wordBuffer.append(char)
            } else if (char.isWhitespace() || char in listOf('.', ',', '!', '?', ';', ':', '-', '_')) {
                // Word boundary detected
                val word = wordBuffer.toString().trim()
                if (word.isNotEmpty()) {
                    Log.d(TAG, "Processing word (from commit): '$word'")
                    processWord(word)
                }
                wordBuffer.clear()
            }
        }
    }

    /**
     * Processes a completed word against the NBOW
     * Matches case-insensitively and checks for partial matches
     */
    private fun processWord(word: String) {
        if (word.isEmpty() || nbow == null) {
            Log.d(TAG, "Skipping empty word or null NBOW")
            return
        }
        
        val wordLower = word.lowercase()
        var wordScore = 0
        
        Log.d(TAG, "Checking word: '$wordLower' against NBOW")
        
        // Iterate through categories in NBOW
        val keys = nbow!!.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            val categoryObj = nbow!!.getJSONObject(category)
            val words = categoryObj.getJSONArray("words")
            val weight = categoryObj.getInt("weight")
            
            // Check each word in the category
            for (i in 0 until words.length()) {
                val nbowWord = words.getString(i).lowercase()
                
                // Case-insensitive match: exact or contains
                if (wordLower == nbowWord || wordLower.contains(nbowWord) || nbowWord.contains(wordLower)) {
                    wordScore += weight
                    Log.d(TAG, "Match found! Word: '$wordLower' matches '$nbowWord' in category '$category' (weight: $weight)")
                    break // Count each category match only once per word
                }
            }
        }
        
        if (wordScore > 0) {
            runningScore += wordScore
            Log.d(TAG, "Score updated: $runningScore/$threshold (added $wordScore)")
            
            // Check if threshold exceeded
            if (runningScore >= threshold) {
                Log.w(TAG, "THRESHOLD EXCEEDED! Triggering alert...")
                triggerSafetyAlert()
            }
        } else {
            Log.d(TAG, "No match for word: '$wordLower'")
        }
    }

    /**
     * Triggers safety alert: shows blocking activity and sends SMS
     */
    private fun triggerSafetyAlert() {
        Log.w(TAG, "========== TRIGGERING SAFETY ALERT! ==========")
        
        // Show blocking overlay activity
        try {
            val intent = Intent(this, BlockingActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            Log.d(TAG, "BlockingActivity started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BlockingActivity", e)
            e.printStackTrace()
        }
        
        // Send SMS to guardian if number is configured
        if (guardianNumber.isNotEmpty()) {
            sendSMSAlert()
        } else {
            Log.w(TAG, "No guardian number configured, skipping SMS")
        }
        
        // Reset score after triggering
        runningScore = 0
    }

    /**
     * Sends SMS alert to guardian number
     */
    private fun sendSMSAlert() {
        try {
            // Check SMS permission
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "SMS permission not granted!")
                return
            }

            val smsManager: SmsManager = SmsManager.getDefault()
            val message = "Alert: unsafe typing detected. Score: $runningScore"
            
            Log.d(TAG, "Sending SMS to: $guardianNumber")
            Log.d(TAG, "Message: $message")
            
            smsManager.sendTextMessage(guardianNumber, null, message, null, null)
            Log.d(TAG, "SMS sent successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending SMS", e)
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            e.printStackTrace()
        }
    }

    /**
     * Public method to reset the running score (can be called from BlockingActivity)
     */
    fun resetScore() {
        runningScore = 0
    }

    /**
     * Public method to get current score (for debugging/monitoring)
     */
    fun getCurrentScore(): Int {
        return runningScore
    }
}

