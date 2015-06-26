/*******************************************************************************
 * Copyright (c) 2013-2015 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.tlv;

import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.leshan.tlv.Tlv.TlvType;
import org.eclipse.leshan.util.Charsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TlvDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(TlvDecoder.class);

    public static Tlv[] decode(ByteBuffer input) throws TlvException {

        try {
            List<Tlv> tlvs = new ArrayList<>();

            while (input.remaining() > 0) {
                input.order(ByteOrder.BIG_ENDIAN);

                // decode type
                int typeByte = input.get() & 0xFF;
                TlvType type;
                switch (typeByte & 0b1100_0000) {
                case 0b0000_0000:
                    type = TlvType.OBJECT_INSTANCE;
                    break;
                case 0b0100_0000:
                    type = TlvType.RESOURCE_INSTANCE;
                    break;
                case 0b1000_0000:
                    type = TlvType.MULTIPLE_RESOURCE;
                    break;
                case 0b1100_0000:
                    type = TlvType.RESOURCE_VALUE;
                    break;
                default:
                    throw new TlvException("unknown type: " + (typeByte & 0b1100_0000));
                }

                // decode identifier
                int identifier;
                try {
                    if ((typeByte & 0b0010_0000) == 0) {
                        identifier = input.get() & 0xFF;
                    } else {
                        identifier = input.getShort() & 0xFFFF;
                    }
                } catch (BufferUnderflowException e) {
                    throw new TlvException("Invalid 'identifier' length", e);
                }
                LOG.trace("decoding {} {}", type, identifier);

                // decode length
                int length;
                int lengthType = typeByte & 0b0001_1000;
                try {
                    switch (lengthType) {
                    case 0b0000_0000:
                        // 2 bit length
                        length = typeByte & 0b0000_0111;
                        break;
                    case 0b0000_1000:
                        // 8 bit length
                        length = input.get() & 0xFF;
                        break;
                    case 0b0001_0000:
                        // 16 bit length
                        length = input.getShort() & 0xFFFF;
                        break;
                    case 0b0001_1000:
                        // 24 bit length
                        length = ((input.get() & 0xFF) << 16) + input.getShort() & 0xFFFF;
                        break;
                    default:
                        throw new TlvException("unknown length type: " + (typeByte & 0b0001_1000));
                    }
                } catch (BufferUnderflowException e) {
                    throw new TlvException("Invalid 'length' length", e);
                }
                LOG.trace("length: {} (length type: {})", length, lengthType);

                // decode value
                if (type == TlvType.RESOURCE_VALUE || type == TlvType.RESOURCE_INSTANCE) {
                    try {
                        byte[] payload = new byte[length];
                        input.get(payload);
                        tlvs.add(new Tlv(type, null, payload, identifier));

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("payload value: {}", DatatypeConverter.printHexBinary(payload));
                        }
                    } catch (BufferOverflowException e) {
                        throw new TlvException("Invalid 'value' length", e);
                    }
                } else {
                    try {
                        // create a view of the contained TLVs
                        ByteBuffer slice = input.slice();
                        slice.limit(length);

                        Tlv[] children = decode(slice);

                        // skip the children, it will be decoded by the view
                        input.position(input.position() + length);

                        Tlv tlv = new Tlv(type, children, null, identifier);
                        tlvs.add(tlv);
                    } catch (IllegalArgumentException e) {
                        throw new TlvException("Invalid 'value' length", e);
                    }
                }
            }

            return tlvs.toArray(new Tlv[] {});
        } catch (TlvException ex) {
            String printHexBinary = DatatypeConverter.printHexBinary(input.array());
            throw new TlvException("Impossible to parse TLV: \n" + printHexBinary, ex);
        } catch (RuntimeException ex) {
            String printHexBinary = DatatypeConverter.printHexBinary(input.array());
            throw new TlvException("Unexpected TLV parse error: \n" + printHexBinary, ex);
        }
    }

    /**
     * Decodes a byte array into string value.
     */
    public static String decodeString(byte[] value) {
        return new String(value, Charsets.UTF_8);
    }

    /**
     * Decodes a byte array into a boolean value.
     */
    public static boolean decodeBoolean(byte[] value) throws TlvException {
        if (value.length == 1) {
            if (value[0] == 0) {
                return false;
            } else if (value[0] == 1) {
                return true;
            } else {
                LOG.warn("Boolean value should be encoded as integer with value 0 or 1, not {}", value[0]);
                return false;
            }
        }
        throw new TlvException("Invalid length for a boolean value: " + value.length);
    }

    /**
     * Decodes a byte array into a date value.
     */
    public static Date decodeDate(byte[] value) throws TlvException {
        if (value.length <= 8) {
            return new Date(decodeInteger(value).longValue() * 1000L);
        } else {
            throw new TlvException("Invalid length for a time value: " + value.length);
        }
    }

    /**
     * Decodes a byte array into an integer value (signed magnitude representation)
     */
    public static Number decodeInteger(byte[] value) throws TlvException {
        boolean positive = (value[0] & 0b1000_0000) == 0; // last bit to 0?
        if (!positive) {
            // convert to positive value by setting the most significant bit to 0
            value[0] &= 0b0111_1111;
        }

        BigInteger buf = new BigInteger(value);
        if (value.length == 1) {
            return positive ? buf.byteValue() : -buf.byteValue();
        } else if (value.length <= 2) {
            return positive ? buf.shortValue() : -buf.shortValue();
        } else if (value.length <= 4) {
            return positive ? buf.intValue() : -buf.intValue();
        } else if (value.length <= 8) {
            return positive ? buf.longValue() : -buf.longValue();
        } else {
            throw new TlvException("Invalid length for an integer value: " + value.length);
        }
    }

    /**
     * Decodes a byte array into a float value.
     */
    public static Number decodeFloat(byte[] value) throws TlvException {
        ByteBuffer floatBb = ByteBuffer.wrap(value);
        if (value.length == 4) {
            return floatBb.getFloat();
        } else if (value.length == 8) {
            return floatBb.getDouble();
        } else {
            throw new TlvException("Invalid length for a float value: " + value.length);
        }
    }
}
