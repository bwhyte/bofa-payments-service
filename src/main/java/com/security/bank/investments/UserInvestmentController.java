package com.security.bank.investments;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.security.bank.dto.InvestmentDto;
import com.security.bank.service.InvestmentService;

@RestController
@RequestMapping("/invest")
public class UserInvestmentController {
    @Autowired
    InvestmentService investmentService;

    @PostMapping("/now")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('CUSTOMER')")
    public String investNow(@RequestParam Long accountId, @RequestBody InvestmentDto investmentDto) {
	return investmentService.investNow(accountId, investmentDto);
    }

}