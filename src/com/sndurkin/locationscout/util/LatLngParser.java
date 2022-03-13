package com.sndurkin.locationscout.util;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

// Holds a function for parsing lat/lon coordinates as user input into
// floating-point numbers that can be used in the app.
public class LatLngParser {

    private enum TokenType {
        LAT_SIGN,
        LON_SIGN,
        UNKNOWN_NUMBER,
        DEGREES,
        MINUTES,
        SECONDS,
        SEPARATOR
    }

    // Returns a valid LatLng point if the input string is able to be
    // parsed into coordinates, returns null otherwise.
    //
    // This function is covered by tests under MiscUtilsTest.java.
    public static LatLng parse(String input) {
        input = input.trim().toUpperCase();

        // Tokenize the input.
        List<TokenType> tokenTypes = new ArrayList<>();
        List<Object> tokens = new ArrayList<>();
        if(!tokenizeLatLngCoords(input, tokenTypes, tokens)) {
            return null;
        }

        // Massage the tokens to make sure the input is correct.
        if(!addSeparators(tokenTypes, tokens)) {
            return null;
        }
        if(!resolveUnknownsIfNeeded(tokenTypes)) {
            return null;
        }

        // Extract coordinates from the tokens.
        LatLng latLng;
        if((latLng = extractLatLngFromTokens(tokenTypes, tokens)) == null) {
            return null;
        }

        // Validate the coordinates.
        if(!validate(latLng)) {
            return null;
        }

        return latLng;
    }

    private static boolean tokenizeLatLngCoords(String input, List<TokenType> tokenTypes, List<Object> tokens) {
        String currentToken = "";
        TokenType currentTokenType = null;

        for(int i = 0; i < input.length(); ++i) {
            char ch = input.charAt(i);

            if(Character.isLetter(ch)) {
                if(ch == 'N' || ch == 'S' || ch == 'E' || ch == 'W') {
                    if(ch == 'N' || ch == 'S') {
                        tokenTypes.add(TokenType.LAT_SIGN);
                    }
                    else {
                        tokenTypes.add(TokenType.LON_SIGN);
                    }

                    if(ch == 'N' || ch == 'E') {
                        tokens.add(1);
                    }
                    else {
                        tokens.add(-1);
                    }
                }
                else {
                    // Failed to tokenize the input.
                    return false;
                }
            }
            else if(Character.isDigit(ch) || ch == '.') {
                currentToken += ch;
                currentTokenType = TokenType.UNKNOWN_NUMBER;
            }
            else if(Character.isSpaceChar(ch)) {
                if(currentTokenType == TokenType.UNKNOWN_NUMBER) {
                    tokenTypes.add(TokenType.UNKNOWN_NUMBER);
                    tokens.add(currentToken);

                    currentTokenType = null;
                    currentToken = "";
                }
            }
            else if(ch == '°' && currentTokenType == TokenType.UNKNOWN_NUMBER) {
                tokenTypes.add(TokenType.DEGREES);
                tokens.add(currentToken);

                currentTokenType = null;
                currentToken = "";
            }
            else if(ch == '\'' && currentTokenType == TokenType.UNKNOWN_NUMBER) {
                tokenTypes.add(TokenType.MINUTES);
                tokens.add(currentToken);

                currentTokenType = null;
                currentToken = "";
            }
            else if(ch == '\"' && currentTokenType == TokenType.UNKNOWN_NUMBER) {
                tokenTypes.add(TokenType.SECONDS);
                tokens.add(currentToken);

                currentTokenType = null;
                currentToken = "";
            }
            else if(ch == ',') {
                if(currentTokenType == TokenType.UNKNOWN_NUMBER) {
                    tokenTypes.add(TokenType.UNKNOWN_NUMBER);
                    tokens.add(currentToken);
                }

                tokenTypes.add(TokenType.SEPARATOR);
                tokens.add(null);

                currentTokenType = null;
                currentToken = "";
            }
            else if(ch == '+' || ch == '-') {
                tokenTypes.add((i == 0) ? TokenType.LAT_SIGN : TokenType.LON_SIGN);
                tokens.add(ch == '+' ? 1 : -1);
            }
            else {
                // Failed to tokenize the input.
                return false;
            }
        }

        if(currentTokenType != null) {
            tokenTypes.add(currentTokenType);
            tokens.add(currentToken);
        }

        return true;
    }

    // Tries to insert a SEPARATOR token in the proper place (if necessary). It also appends a SEPARATOR token
    // to the end, to make the list more consistent and easier to parse.
    private static boolean addSeparators(List<TokenType> tokenTypes, List<Object> tokens) {
        if(!tokenTypes.contains(TokenType.SEPARATOR)) {
            if(!tokenTypes.contains(TokenType.UNKNOWN_NUMBER)) {
                boolean foundLatDegrees = false,
                        foundLonDegrees = false;
                for(int i = 0; i < tokenTypes.size(); ++i) {
                    TokenType tokenType = tokenTypes.get(i);
                    if(tokenType == TokenType.DEGREES || tokenType == TokenType.LON_SIGN) {
                        if(foundLatDegrees) {
                            foundLonDegrees = true;
                            tokenTypes.add(i, TokenType.SEPARATOR);
                            tokens.add(i, null);
                            break;
                        }
                        else {
                            foundLatDegrees = true;
                        }
                    }
                }

                if(!foundLatDegrees || !foundLonDegrees) {
                    return false;
                }
            }
            else {
                // We just have a bunch of unknown numbers, so try to convert them into degrees/minutes/seconds.
                int numUnknownNumbers = 0;
                for(int i = 0; i < tokenTypes.size(); ++i) {
                    TokenType tt = tokenTypes.get(i);
                    if(tt == TokenType.UNKNOWN_NUMBER) {
                        ++numUnknownNumbers;
                    }
                }

                if(numUnknownNumbers % 2 != 0) {
                    // It should have an even number of numbers;
                    return false;
                }

                int numLatitudeNumbers = 0;
                numUnknownNumbers /= 2;
                for(int i = 0; i < tokenTypes.size(); ++i) {
                    TokenType tt = tokenTypes.get(i);
                    if(tt == TokenType.UNKNOWN_NUMBER) {
                        if(++numLatitudeNumbers == numUnknownNumbers) {
                            // We found the middle of the unknown numbers, so
                            // let's add a separator.
                            tokenTypes.add(i+1, TokenType.SEPARATOR);
                            tokens.add(i+1, null);
                            break;
                        }
                    }
                }
            }
        }

        // Add a separator at the end to make it easy to set the latitude
        // and longitude in the same place.
        tokenTypes.add(TokenType.SEPARATOR);
        tokens.add(null);

        return true;
    }

    private static boolean resolveUnknownsIfNeeded(List<TokenType> tokenTypes) {
        if(!tokenTypes.contains(TokenType.UNKNOWN_NUMBER)) {
            return true;
        }

        boolean degreesSet = false,
                minutesSet = false,
                secondsSet = false;
        for(int i = 0; i < tokenTypes.size(); ++i) {
            TokenType tt = tokenTypes.get(i);
            if(tt == TokenType.UNKNOWN_NUMBER) {
                if(!degreesSet) {
                    tokenTypes.set(i, TokenType.DEGREES);
                    degreesSet = true;
                }
                else if(!minutesSet) {
                    tokenTypes.set(i, TokenType.MINUTES);
                    minutesSet = true;
                }
                else if(!secondsSet) {
                    tokenTypes.set(i, TokenType.SECONDS);
                    secondsSet = true;
                }
                else {
                    // We've apparently hit another unknown number before the separator
                    // and have already set the degrees, minutes and seconds.
                    return false;
                }
            }
            else if(tt == TokenType.SEPARATOR) {
                degreesSet = minutesSet = secondsSet = false;
            }
            else if(tt == TokenType.DEGREES || tt == TokenType.MINUTES || tt == TokenType.SECONDS) {
                // There should not be unknown and known numbers in the same input.
                return false;
            }
        }

        return true;
    }

    private static LatLng extractLatLngFromTokens(List<TokenType> tokenTypes, List<Object> tokens) {
        int     sign = 1;
        Double  degrees = null,
                minutes = null,
                seconds = null;

        Double  latitude = null,
                longitude = null;

        for(int i = 0; i < tokenTypes.size(); ++i) {
            TokenType tokenType = tokenTypes.get(i);
            Object token = tokens.get(i);

            if(tokenType == TokenType.LAT_SIGN || tokenType == TokenType.LON_SIGN) {
                sign = (Integer) token;
            }
            else if(tokenType == TokenType.UNKNOWN_NUMBER) {
                if(degrees == null) {
                    degrees = Double.parseDouble(token.toString());
                }
                else if(minutes == null) {
                    minutes = Double.parseDouble(token.toString());
                }
                else if(seconds == null) {
                    seconds = Double.parseDouble(token.toString());
                }
            }
            else if(tokenType == TokenType.DEGREES) {
                degrees = Double.parseDouble(token.toString());
            }
            else if(tokenType == TokenType.MINUTES) {
                minutes = Double.parseDouble(token.toString());
            }
            else if(tokenType == TokenType.SECONDS) {
                seconds = Double.parseDouble(token.toString());
            }
            else if(tokenType == TokenType.SEPARATOR) {
                if(latitude == null) {
                    if(degrees == null) {
                        return null;
                    }

                    if(minutes != null) {
                        degrees += minutes / 60.0;
                        if(seconds != null) {
                            degrees += seconds / 3600.0;
                        }
                    }
                    else if(seconds != null) {
                        return null;
                    }

                    latitude = degrees * sign;
                    degrees = minutes = seconds = null;
                    sign = 1;
                }
                else if(longitude == null) {
                    if(degrees == null) {
                        return null;
                    }

                    if(minutes != null) {
                        degrees += minutes / 60.0;
                        if(seconds != null) {
                            degrees += seconds / 3600.0;
                        }
                    }
                    else if(seconds != null) {
                        return null;
                    }

                    longitude = degrees * sign;
                    degrees = minutes = seconds = null;
                }
                else {
                    return null;
                }
            }
        }

        if(latitude == null || longitude == null) {
            return null;
        }

        return new LatLng(latitude, longitude);
    }

    private static boolean validate(LatLng latLng) {
        return latLng.latitude >= -90 && latLng.latitude <= 90
            && latLng.longitude >= -180 && latLng.longitude <= 180;
    }

}
