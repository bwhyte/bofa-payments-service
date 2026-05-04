package com.security.bank.entity;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Entity
@Data
public class Investment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private InvestmentType investmentType;

    private String risk;

    private double amount;

    private float returns;

    private String duration;

    private String companyName;

    @ManyToOne
    @JsonIgnoreProperties("investmentList")
    @JoinColumn(name = "user_id")
    private User user;

}
