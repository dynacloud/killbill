/*
* To change this license header, choose License Headers in Project Properties.
* To change this template file, choose Tools | Templates
* and open the template in the editor.
*/

package org.killbill.billing.invoice.calculator;
import org.joda.time.DateTime;
import org.joda.time.DateTimeComparator;

/**
*
* @author michiel
*/
public class TaxFactorItem {
    
    private DateTime dateTime;
    private double taxFactor1;
    private double taxFactor2;
    private double taxFactor3;
    
    public TaxFactorItem(int year, int month, int day, double taxFactor1) {
        internalConstructor(year, month, day);
        this.taxFactor1 = taxFactor1;
    }
    
    public TaxFactorItem(int year, int month, int day, double taxFactor1, double taxFactor2) {
        internalConstructor(year, month, day);
        this.taxFactor1 = taxFactor1;
        this.taxFactor2 = taxFactor2;
    }
    
    public TaxFactorItem(int year, int month, int day, double taxFactor1, double taxFactor2, double taxFactor3) {
        internalConstructor(year, month, day);
        this.taxFactor1 = taxFactor1;
        this.taxFactor2 = taxFactor2;
        this.taxFactor3 = taxFactor3;
    }
    
    private void internalConstructor(int year, int month, int day) {
        this.dateTime = new DateTime(year, month, day, 0, 0);
    }
    
    public boolean isInEffect(DateTime date) {
        DateTimeComparator comparator = DateTimeComparator.getInstance();
        return comparator.compare(date, this.dateTime) > 0;
    }
    
    public double getTaxFactor1() {
        return this.taxFactor1;
    }

    public double getTaxFactor2() {
        return this.taxFactor2;
    }

    public double getTaxFactor3() {
        return this.taxFactor3;
    }
}