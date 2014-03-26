/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.collection;

import java.util.Arrays;
import java.util.List;

/**
 * Class that contains utility methods for List
 */
public class ListUtil {

    /**
     * Convert to string a list of T objects.  This is the List version of
     * Arrays.toString.  Just like in Arrays.toString, all null objects are
     * converted to strings 'null', including the input argument.  Thus,
     * it is safe to call this method and pass in null as the argument.
     *
     * @param list List of objects to convert to string
     * @param <T> Type of objects stored in the List
     * @return String representation of the provided list
     */
    public static <T> String toString(List<T> list) {

        return list == null ? "null" : Arrays.toString(list.toArray());
    }

}