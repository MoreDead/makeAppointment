# Venue-to-Postcode Address Extraction

## Problem Resolved

**Issue**: The location field was showing all the selected text instead of just the address portion from venue name to postcode.

**Solution**: Implemented intelligent venue-to-postcode extraction that identifies where the address starts (venue/building name) and ends (postcode/ZIP), extracting only the relevant address information.

## Key Improvements

### 1. **Enhanced Gemini Prompts**

**Image Analysis Prompt** now specifies:
```
- For LOCATION: Extract ONLY the address portion, starting from venue/hospital/clinic name and ending with postcode/ZIP
- DO NOT include appointment details, times, dates, or other non-address text
- Start from: Hospital name, Clinic name, Medical Center, Practice name, or building name
- End at: Postcode (UK: SW1A 1AA), ZIP code (US: 12345), or last line of address
- Include: Venue name, street address, city, state/county, postcode
- Exclude: Appointment time, date, doctor names (unless part of practice name), phone numbers
```

**OCR Text Analysis Prompt** now includes:
```
- For LOCATIONS: Extract ONLY the address portion, starting from venue/facility name and ending with postcode
- Start address extraction from: Hospital name, Clinic name, Medical Center, Surgery, Practice name, or building name
- End address extraction at: Postcode (UK: SW1A 1AA), ZIP code (US: 12345), or Canadian postal code
- Include in address: Venue name, street number and name, city, state/county, postcode
- Exclude from address: Appointment times, dates, phone numbers, doctor names (unless part of practice name)
```

### 2. **New `extractVenueToPostcode` Function**

Added intelligent logic to identify:

**Venue Start Patterns**:
- Medical facilities: `Hospital`, `Medical Center`, `Clinic`, `Health Center`, `Surgery`, `Practice`, `GP Practice`
- General venues: Building names with medical/facility keywords
- Doctor practices: `Dr Smith's Surgery`, `Doctor Jones Practice`
- Address-like capitalized text with street/number patterns

**Postcode End Patterns**:
- UK: `SW1A 1AA`, `M1 1AA`, `B33 8TH`
- US ZIP: `12345`, `12345-6789`
- Canadian: `K1A 0A6`

**Extraction Logic**:
```kotlin
// If both venue and postcode found: extract from venue line to postcode line
if (venueStartIndex != -1 && postcodeEndIndex != -1) {
    val addressLines = lines.subList(venueStartIndex, postcodeEndIndex + 1)
    return addressLines.joinToString(", ")
}

// If only venue found: take up to 3 lines from venue
if (venueStartIndex != -1) {
    val endIndex = minOf(venueStartIndex + 3, lines.size)
    val addressLines = lines.subList(venueStartIndex, endIndex)
    return addressLines.joinToString(", ")
}

// If only postcode found: work backwards up to 3 lines
if (postcodeEndIndex != -1) {
    val startIndex = maxOf(0, postcodeEndIndex - 2)
    val addressLines = lines.subList(startIndex, postcodeEndIndex + 1)
    return addressLines.joinToString(", ")
}
```

### 3. **Improved Local Processing**

**Priority System**:
1. Try `extractVenueToPostcode` first (most precise)
2. Fall back to general postcode-based extraction
3. Fall back to address-pattern matching

**Result Prioritization**: Complete venue-to-postcode addresses are prioritized over fragmentary location matches.

## Examples of Extraction

### Before Enhancement
**Input Text**:
```
Appointment Details
Date: Monday 15th January 2024
Time: 10:30 AM
Dr. Smith's Surgery
123 Main Street
Anytown
County
AB1 2CD
Tel: 01234 567890
```

**Previous Result**: All the above text as location

### After Enhancement
**Input Text**: Same as above

**New Result**: `Dr. Smith's Surgery, 123 Main Street, Anytown, County, AB1 2CD`

### More Examples

| Input Scenario | Venue Start | Postcode End | Extracted Address |
|---------------|-------------|--------------|-------------------|
| Full medical address | `City Hospital` | `M1 1AA` | `City Hospital, 456 Hospital Road, Manchester, M1 1AA` |
| Practice with postcode | `Green Surgery` | `SW1A 1AA` | `Green Surgery, 789 Green Lane, London, SW1A 1AA` |
| US ZIP code | `Medical Center` | `12345` | `Medical Center, 100 Health Ave, Springfield, 12345` |
| Venue only | `Clinic` | (none) | `Clinic, Next Street, Local Area` |
| Postcode only | (none) | `B33 8TH` | `Some Street, Birmingham, B33 8TH` |

## Technical Features

### Venue Detection Patterns
- **Medical Facilities**: Hospital, Medical Center, Clinic, Health Center, Surgery, Practice, Centre, GP Practice
- **General Buildings**: Building, Tower, Plaza, Complex, Office
- **Doctor Names**: Dr Smith's Surgery, Doctor Jones Practice
- **Capitalized Text**: Lines starting with capitals containing address-like terms

### Postcode Detection Patterns
- **UK**: `[A-Z]{1,2}\d{1,2}[A-Z]?\s+\d[A-Z]{2}` (SW1A 1AA, M1 1AA, B33 8TH)
- **US ZIP**: `\d{5}(-\d{4})?` (12345, 12345-6789)
- **Canadian**: `[A-Z]\d[A-Z]\s+\d[A-Z]\d` (K1A 0A6)

### Smart Fallbacks
1. If venue + postcode found → Extract exact range
2. If only venue found → Take venue + next 2-3 lines
3. If only postcode found → Take postcode + previous 2-3 lines
4. If neither found → Use existing pattern matching

### Debug Logging
Added comprehensive logging to track:
- Venue detection at specific line indices
- Postcode detection with matched patterns
- Extraction decisions and results
- Fallback logic activation

## Benefits

1. **Precise Extraction**: Only address information, no appointment details
2. **Smart Boundaries**: Knows where addresses start and end
3. **Multi-Country Support**: UK, US, and Canadian address formats
4. **Fallback Robustness**: Still works if venue or postcode missing
5. **OCR Error Tolerance**: Handles scrambled text and missing information
6. **Clean Results**: No phone numbers, appointment times, or irrelevant text

## Testing

Build and test with:
```bash
./gradlew installDebug
```

The app will now extract precise venue-to-postcode addresses instead of all selected text, providing clean, usable location information that can be directly used in mapping applications or contacts.