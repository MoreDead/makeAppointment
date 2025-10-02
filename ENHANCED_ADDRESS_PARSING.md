# Enhanced Address Parsing for Gemini

## Overview

The appointment app has been enhanced with intelligent address parsing and formatting capabilities. Gemini will now parse venue information and reorder address components into proper, valid address format before returning them.

## Key Improvements

### 1. Enhanced Gemini Prompts

**Image Analysis Prompt** now includes:
- Instructions to parse and format addresses properly
- Reorder address components into standard format: `[Building/Venue], [Street Number Street Name], [City], [State/Region], [Postcode/ZIP]`
- Fix scrambled addresses where words may be in wrong order due to OCR errors
- Example corrections like: "Road Hospital Main 123" → "Hospital, 123 Main Road"

**OCR Text Analysis Prompt** now includes:
- Similar address formatting instructions
- Specific examples of common OCR scrambling patterns
- Instructions to combine fragmented address parts

### 2. Local Address Enhancement Function

Added `enhanceAddressFormatting()` function that:
- Categorizes address components (building names, street addresses, cities, postcodes, room/suite info)
- Reorders components into proper address hierarchy
- Handles scrambled single-string addresses
- Supports UK, US, and Canadian address formats

### 3. Smart Component Detection

The system now recognizes:
- **Postcodes/ZIP codes**: UK (SW1A 1AA), US (12345), Canadian (K1A 0A6)
- **Building types**: Hospital, Clinic, Medical Center, Practice, Surgery
- **Street types**: Street, Road, Avenue, Drive, Lane, Boulevard, etc.
- **Room/Suite identifiers**: Room 101, Suite A, Unit 5, etc.
- **City patterns**: Capitalized words without address keywords

## Examples of Address Corrections

### Before Enhancement
- Raw OCR: "Road Hospital Main 123 London SW1A 1AA"
- Old output: "Road Hospital Main 123 London SW1A 1AA"

### After Enhancement
- Raw OCR: "Road Hospital Main 123 London SW1A 1AA"
- Enhanced output: "Main Hospital, 123 Road, London, SW1A 1AA"

### More Examples
| Scrambled Input | Enhanced Output |
|-----------------|----------------|
| "Suite London 5 Street Baker" | "5 Baker Street, Suite, London" |
| "Center Medical City Health" | "City Health Medical Center" |
| "Building Tower 10 High Street" | "Tower Building, 10 High Street" |
| "Room 205 Hospital General" | "General Hospital, Room 205" |

## Implementation Details

### Gemini Prompt Enhancements

1. **Image Analysis** (`extractFromImageWithGemini`):
   - Added specific address formatting rules
   - Instructions to identify and reorder scrambled components
   - Standard address hierarchy enforcement

2. **OCR Text Analysis** (`performAIExtraction`):
   - Enhanced with address parsing examples
   - Instructions to fix OCR-induced word scrambling
   - Multiple location handling with proper formatting

### Address Processing Pipeline

1. **Gemini Processing**: AI analyzes and initially formats the address
2. **Local Enhancement**: `enhanceAddressFormatting()` further refines the result
3. **Component Categorization**: Identifies building names, street addresses, cities, postcodes
4. **Reordering**: Assembles components in proper address hierarchy
5. **Validation**: Ensures logical address structure

### Supported Address Formats

- **UK**: Building Name, Street Number Street Name, City, County, Postcode
- **US**: Building Name, Street Number Street Name, City, State, ZIP Code
- **Canadian**: Building Name, Street Number Street Name, City, Province, Postal Code

## Testing the Enhancement

To test the enhanced address parsing:

1. Build and run the app: `./gradlew installDebug`
2. Select an image containing appointment details with addresses
3. The system will now return properly formatted addresses even if the original text has scrambled components

## Benefits

1. **Improved Accuracy**: Addresses are now in standard, recognizable format
2. **Better Usability**: Properly formatted addresses can be used directly in navigation apps
3. **Reduced Manual Correction**: Less need for users to manually fix address formatting
4. **Geocoding Compatibility**: Standard format works better with mapping services
5. **Professional Presentation**: Clean, properly ordered address information

## Configuration

No additional configuration is required. The enhancement works automatically for both:
- Direct image analysis with Gemini Vision
- OCR text processing with Gemini Pro

The system gracefully falls back to the original address if parsing fails, ensuring robustness.