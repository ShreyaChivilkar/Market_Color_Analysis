package com.restapi.market.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;
import static java.util.Map.Entry.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.restapi.market.model.AverageValues;
import com.restapi.market.model.Calculate;
import com.restapi.market.model.Company;
import com.restapi.market.model.DailyData;

import com.restapi.market.model.Stock;

import com.restapi.market.repository.CompanyRepository;

@Service
public class CompanyService {

	@Value("${token}")
	private String token;

	@Value("${boundary.date}")
	private String boundaryDate;

	private static String url1 = "https://sandbox.iexapis.com/stable/stock/";
	private static String url2_initial = "/chart/ytd?chartCloseOnly=true&token=";
	private static String url2_new = "/chart/ytd?chartLast=1&chartCloseOnly=true&token=";
	@Autowired
	private RestTemplate restTemplate;

	@Autowired
	private CompanyRepository companyRepository;

	@Autowired
	private MongoTemplate mongoTemplate;

	SimpleDateFormat converter = new SimpleDateFormat("yyyy-MM-dd");

	Calendar cal = Calendar.getInstance();

	// returns company object when ticker is passed
	public Company getByTicker(String ticker) {
		return this.companyRepository.findByTicker(ticker);
	}

	// returns list of company objects belonging to a given sector
	public List<Company> getBySector(String sector) {
		return this.companyRepository.findBySector(sector);
	}

	// get list of all tickers in database
	public List<String> getAllTickers() {
		return mongoTemplate.query(Company.class).distinct("ticker").as(String.class).all();
	}

	// get list of all sectors in database
	public List<String> getAllSectors() {
		return mongoTemplate.query(Company.class).distinct("sector").as(String.class).all();
	}

	// seed database on company basis
	public String addStocksByTicker(String ticker) throws ParseException {
		Company company = this.companyRepository.findByTicker(ticker);
		Stock[] stocks = restTemplate.getForObject(url1 + ticker + url2_initial + token, Stock[].class);
		for (Stock stock : stocks) {
			Date nowDate = converter.parse(stock.getDate());
			Date thresholdDate = converter.parse(boundaryDate);
			cal.setTime(nowDate);
			int week_no = cal.get(Calendar.WEEK_OF_YEAR);
			String week = null;
			week = Integer.toString(week_no);
			stock.setWeek(week);
			stock.setMonth(stock.getDate().substring(5, 7));

			if (nowDate.before(thresholdDate) || nowDate.equals(thresholdDate)) {
				stock.setPeriod("pre");
			} else {
				stock.setPeriod("post");
			}

		}
		company.setStocks(Arrays.asList(stocks));
		this.companyRepository.save(company);
		return ticker + "information added to DB";
	}

	// seed database with data of all companies
	public String seedDb() {
		List<String> tickers = mongoTemplate.query(Company.class).distinct("ticker").as(String.class).all();
		for (String ticker : tickers) {
			try {
				addStocksByTicker(ticker);
			} catch (Exception exception) {
				System.out.println("Did not find " + ticker);
			}
		}
		return "Seeding Successful!";
	}

	// daily update of stocks data of company whose ticker is passed
	public void updateByTicker(String ticker) throws ParseException {
		Stock[] stocks = restTemplate.getForObject(url1 + ticker + url2_new + token, Stock[].class); // returns only one
																										// object
		for (Stock stock : stocks) {

			Date nowDate = converter.parse(stock.getDate());
			cal.setTime(nowDate);
			int week_no = cal.get(Calendar.WEEK_OF_YEAR);
			String week = null;
			week = Integer.toString(week_no);
			stock.setWeek(week);
			stock.setMonth(stock.getDate().substring(5, 7));
			Date thresholdDate = converter.parse(boundaryDate);
			if (nowDate.before(thresholdDate) || nowDate.equals(thresholdDate)) {
				stock.setPeriod("pre");
			} else {
				stock.setPeriod("post");
			}

		}
		mongoTemplate.updateFirst(new Query(Criteria.where("ticker").is(ticker)),
				new Update().addToSet("stocks", stocks[0]), Company.class);
	}

	// daily update of stocks data for all companies
	public void dailyUpdateAll() {
		List<String> tickers = mongoTemplate.query(Company.class).distinct("ticker").as(String.class).all();
		for (String ticker : tickers) {
			try {
				updateByTicker(ticker);
			} catch (Exception exception) {
				System.out.println(exception);
			}
		}
	}

	// calculate average volume for a company by ticker
	public AverageValues calAvgVolumeByCompany(String ticker) {
		Company company = getByTicker(ticker);
		AverageValues volumeAverage = new AverageValues();
		double sum_volume_pre = 0;
		double sum_volume_post = 0;
		int sizeofpre = 0;
		List<Stock> stocks = company.getStocks();
		for (Stock stock : stocks) {
			if (stock.getPeriod().contentEquals("pre")) {
				sizeofpre = sizeofpre + 1;
				sum_volume_pre += stock.getVolume();
			} else {
				sum_volume_post += stock.getVolume();
			}
		}

		volumeAverage.setPreCovidValue((sum_volume_pre) / (sizeofpre));
		volumeAverage.setPostCovidValue((sum_volume_post) / (stocks.size() - sizeofpre));
		volumeAverage.setDeviation(volumeAverage.getPostCovidValue() - volumeAverage.getPreCovidValue());

		return volumeAverage;

	}

	// calculate average stock-price for a company by ticker
	public AverageValues calAvgPriceByCompany(String ticker) {
		Company company = getByTicker(ticker);
		AverageValues priceAverage = new AverageValues();
		double sum_close_pre = 0;
		double sum_close_post = 0;
		int sizeofpre = 0;

		List<Stock> stocks = company.getStocks();

		for (Stock stock : stocks) {

			if (stock.getPeriod().contentEquals("pre")) {

				sum_close_pre += stock.getClose();
				sizeofpre = sizeofpre + 1;
			}

			else {
				sum_close_post += stock.getClose();
			}
		}

		priceAverage.setPreCovidValue((sum_close_pre) / (sizeofpre));
		priceAverage.setPostCovidValue((sum_close_post) / (stocks.size() - sizeofpre));
		priceAverage.setDeviation(priceAverage.getPostCovidValue() - priceAverage.getPreCovidValue());

		return priceAverage;

	}

	// calculate average stock-price for a sector
	public AverageValues calAvgPriceBySector(String sector) {
		List<Company> company = getBySector(sector);
		AverageValues priceAverage = new AverageValues();
		double pre_sum_price = 0, post_sum_price = 0;

		for (Company comp : company) {

			pre_sum_price = pre_sum_price + calAvgPriceByCompany(comp.getTicker()).getPreCovidValue();

			post_sum_price = post_sum_price + calAvgPriceByCompany(comp.getTicker()).getPostCovidValue();

		}

		priceAverage.setPreCovidValue((pre_sum_price) / (company.size()));
		priceAverage.setPostCovidValue((post_sum_price) / (company.size()));
		priceAverage.setDeviation(priceAverage.getPostCovidValue() - priceAverage.getPreCovidValue());

		return priceAverage;

	}

	// calculate average volume for a sector
	public AverageValues calAvgVolumeBySector(String sector) {
		List<Company> company = getBySector(sector);

		AverageValues volumeAverage = new AverageValues();
		double pre_sum_volume = 0, post_sum_volume = 0;

		for (Company comp : company) {
			pre_sum_volume = pre_sum_volume + calAvgVolumeByCompany(comp.getTicker()).getPreCovidValue();

			post_sum_volume = post_sum_volume + calAvgPriceByCompany(comp.getTicker()).getPostCovidValue();

		}

		volumeAverage.setPreCovidValue((pre_sum_volume) / (company.size()));
		volumeAverage.setPostCovidValue((post_sum_volume) / (company.size()));
		volumeAverage.setDeviation(volumeAverage.getPostCovidValue() - volumeAverage.getPreCovidValue());

		return volumeAverage;

	}

	// Calculate average values for a company
	public AverageValues CompanyAverage(String ticker, String type) {

		if (type.contentEquals("price")) {

			return calAvgPriceByCompany(ticker);
		}

		else if (type.contentEquals("volume")) {

			return calAvgVolumeByCompany(ticker);
		}

		else {
			return null;
		}
	}

	// Calculate average values for a sector
	public AverageValues SectorAverage(String sector, String type) {

		if (type.contentEquals("price")) {

			return calAvgPriceBySector(sector);
		}

		else if (type.contentEquals("volume")) {

			return calAvgVolumeBySector(sector);
		}

		else {
			return null;
		}
	}

	// to plot pre and post averages of all companies in a sector
	public Map<String, AverageValues> getSectorChart(String sector, String type) {
		List<String> tickerList = getAllTickers();
		Map<String, AverageValues> chart = new HashMap<String, AverageValues>();
		if (type.contentEquals("price")) {
			for (String ticker : tickerList) {
				chart.put(ticker, calAvgPriceByCompany(ticker));
			}
			return chart;
		} else if (type.contentEquals("volume")) {
			for (String ticker : tickerList) {
				chart.put(ticker, calAvgVolumeByCompany(ticker));
			}
			return chart;
		} else {
			return null;
		}

	}

	// Sort Functions for Sector-wise Deviation:

	// Sort Average Volume Deviation of Sectors
	public Map<String, Double> getSectorVolumeDeviation() {
		List<String> SectorList = getAllSectors();
		Map<String, Double> Values = new HashMap<String, Double>();
		for (String i : SectorList) {
			AverageValues volumeAverage = calAvgVolumeBySector(i);
			Values.put(i, volumeAverage.getDeviation());
		}
		Map<String, Double> SortedValues = Values.entrySet().stream().sorted(comparingByValue())
				.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
		return SortedValues;
	}

	// Sort Average stock-price Deviation of Sectors
	public Map<String, Double> getSectorPriceDeviation() {
		List<String> SectorList = getAllSectors();
		Map<String, Double> Values = new HashMap<String, Double>();
		for (String i : SectorList) {

			AverageValues priceAverage = calAvgPriceBySector(i);
			Values.put(i, priceAverage.getDeviation());
		}
		Map<String, Double> SortedValues = Values.entrySet().stream().sorted(comparingByValue())
				.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
		return SortedValues;
	}

	// Sort Functions for Company-wise Deviation:

	// Sort Average Volume Deviation of Company
	public Map<String, Double> getCompanyVolumeDeviation() {
		List<String> TickerList = getAllTickers();
		Map<String, Double> Values = new HashMap<String, Double>();
		for (String i : TickerList) {
			AverageValues volumeAverage = calAvgVolumeByCompany(i);
			Values.put(i, volumeAverage.getDeviation());
		}
		Map<String, Double> SortedValues = Values.entrySet().stream().sorted(comparingByValue())
				.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
		return SortedValues;
	}

	// Sort Average stock-price Deviation of Company
	public Map<String, Double> getCompanyPriceDeviation() {
		List<String> TickerList = getAllTickers();
		Map<String, Double> Values = new HashMap<String, Double>();

		for (String i : TickerList) {
			AverageValues priceAverage = calAvgPriceByCompany(i);
			Values.put(i, priceAverage.getDeviation());
		}

		Map<String, Double> SortedValues = Values.entrySet().stream().sorted(comparingByValue())
				.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
		return SortedValues;
	}

	// Sorted Deviation for Companies
	public Map<String, Double> getDeviationCompany(String rank) {

		if (rank.contentEquals("volume")) {
			return getCompanyVolumeDeviation();
		}

		else {
			return getCompanyPriceDeviation();
		}

	}

	// Sorted Deviation for Sectors
	public Map<String, Double> getDeviationSector(String rank) {
		if (rank.contentEquals("volume")) {
			return getSectorVolumeDeviation();
		}

		else if (rank.contentEquals("price")) {
			return getSectorPriceDeviation();
		} else {
			return null;
		}

	}

	// Calculate Average Stock Price and Volume
	public Calculate averagestock(List<Stock> stocks) {

		Calculate cal = new Calculate();
		double sum_close = 0;
		double sum_volume = 0;
		for (Stock stock : stocks) {
			sum_close += stock.getClose();
			sum_volume += stock.getVolume();
		}
		cal.setPrice(sum_close / stocks.size());
		cal.setVolume(sum_volume / stocks.size());
		return cal;
	}

	// Alternate method

	public Map<String, Double> DataCompany(String ticker, String type) {
		Company company = getByTicker(ticker);
		List<Stock> stocks = company.getStocks();
		if (type.contentEquals("volume")) {
			Map<String, Double> value = stocks.stream()
					.collect(Collectors.groupingBy(Stock::getPeriod, Collectors.averagingDouble(Stock::getVolume)));

			return value;
		} else {
			Map<String, Double> value = stocks.stream()
					.collect(Collectors.groupingBy(Stock::getPeriod, Collectors.averagingDouble(Stock::getClose)));
			return value;

		}

	}

	// Calculate for Average date range by company public Calculate
	public Calculate getDataByRangeCompany(String ticker, String startDate, String endDate) throws ParseException {
		Company company = getByTicker(ticker);
		List<Stock> stocks = company.getStocks();
		List<Stock> stocksnew = new ArrayList<>();
		Date eDate = converter.parse(endDate);
		Date sDate = converter.parse(startDate);
		for (Stock stock : stocks) {

			String nDate = stock.getDate();
			Date nowDate = converter.parse(nDate);
			if (nowDate.before(eDate) && nowDate.after(sDate) || nowDate.equals(sDate) || nowDate.equals(eDate)) {
				stocksnew.add(stock);
			}
		}
		return averagestock(stocksnew);
	}

	// Calculate for Average date range by sector public Calculate
	public Calculate getDataByRangeSector(String sector, String startDate, String endDate) throws ParseException {
		List<Company> companies = getBySector(sector);
		List<Stock> stocksnew = new ArrayList<>();
		Date eDate = converter.parse(endDate);
		Date sDate = converter.parse(startDate);
		for (Company comp : companies) {

			List<Stock> stocks = comp.getStocks();
			for (Stock stock : stocks) {

				String nDate = stock.getDate();
				Date nowDate = converter.parse(nDate);
				if (nowDate.before(eDate) && nowDate.after(sDate) || nowDate.equals(sDate) || nowDate.equals(eDate)) {
					stocksnew.add(stock);
				}
			}
		}
		return averagestock(stocksnew);
	}

/////////////                              DAILY COMPANY                          ////////////
	public Map<String, Double> DailyCompany(String ticker, String frdate, String todate, String type)
			throws ParseException {

		Date toDate = converter.parse(todate);
		Date frDate = converter.parse(frdate);
		Company company = getByTicker(ticker);
		List<Stock> stocknew = new ArrayList<>();

		List<Stock> stocks = company.getStocks();
		for (Stock stock : stocks) {

			Date nDate = converter.parse(stock.getDate());
			if (nDate.before(toDate) && nDate.after(frDate) || nDate.equals(toDate) || nDate.equals(frDate)) {
				stocknew.add(stock);
			}
		}

		if (type.contentEquals("price")) {
			Map<String, Double> value = stocknew.stream()
					.collect(Collectors.groupingBy(Stock::getDate, Collectors.averagingDouble(Stock::getClose)));

			Map<String, Double> daily = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return daily;
		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> value = stocknew.stream()
					.collect(Collectors.groupingBy(Stock::getDate, Collectors.averagingDouble(Stock::getVolume)));

			Map<String, Double> daily = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return daily;

		}

		else {

			System.out.print("Enter correct parameters");
			return null;
		}
	}

/////////////////                     DAILY SECTOR                     /////////////////
	public Map<String, Double> DailySector(String sector, String startdate, String enddate, String type)
			throws ParseException {

		List<Company> company = getBySector(sector);
		Date startDate = converter.parse(startdate);
		Date endDate = converter.parse(enddate);
		List<Stock> stocknew = new ArrayList<>();
		for (Company comp : company) {

			List<Stock> stocks = comp.getStocks();
			for (Stock stock : stocks) {

				Date nDate = converter.parse(stock.getDate());
				if (nDate.before(endDate) && nDate.after(startDate) || nDate.equals(startDate)
						|| nDate.equals(endDate)) {
					stocknew.add(stock);
				}
			}

		}

		if (type.contentEquals("price")) {
			Map<String, Double> value = stocknew.stream()
					.collect(Collectors.groupingBy(Stock::getDate, Collectors.averagingDouble(Stock::getClose)));

			Map<String, Double> daily = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return daily;
		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> value = stocknew.stream()
					.collect(Collectors.groupingBy(Stock::getDate, Collectors.averagingDouble(Stock::getVolume)));

			Map<String, Double> daily = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return daily;

		} else {

			System.out.print("Enter correct parameters");
			return null;
		}

	}

//////////////                      WEEKLY COMPANY              //////////////////////
	public Map<String, Double> WeeklyCompany(String ticker, String startDate, String endDate, String type)
			throws ParseException {
		Company company = getByTicker(ticker);
		List<Stock> stocks = company.getStocks();
		List<Stock> stocksnew = new ArrayList<>();
		Date sDate = converter.parse(startDate);
		Date eDate = converter.parse(endDate);
		for (Stock stock : stocks) {
			Date nowDate = converter.parse(stock.getDate());
			if ((nowDate.after(sDate) && nowDate.before(eDate)) || nowDate.equals(sDate) || nowDate.equals(eDate))
				stocksnew.add(stock);
		}

		if (type.contentEquals("price")) {
			Map<String, Double> value = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getWeek, Collectors.averagingDouble(Stock::getClose)));

			Map<String, Double> weekly = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return weekly;

		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> value = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getWeek, Collectors.averagingDouble(Stock::getVolume)));

			Map<String, Double> weekly = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			return weekly;
		}

		else {

			System.out.print("Enter correct parameters");
			return null;
		}

	}

/////////////////////                 MONTHLY COMPANY              ///////////////////////
	public Map<String, Double> MonthlyCompany(String ticker, String startDate, String endDate, String type)
			throws ParseException {
		Company company = getByTicker(ticker);
		List<Stock> stocks = company.getStocks();
		List<Stock> stocksnew = new ArrayList<>();
		Date sDate = converter.parse(startDate);
		Date eDate = converter.parse(endDate);
		for (Stock stock : stocks) {
			Date nowDate = converter.parse(stock.getDate());
			if ((nowDate.after(sDate) && nowDate.before(eDate)) || nowDate.equals(sDate) || nowDate.equals(eDate)) {
				stocksnew.add(stock);
			}
		}

		if (type.contentEquals("price")) {
			Map<String, Double> monthly = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getMonth, Collectors.averagingDouble(Stock::getClose)));

			return monthly;

		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> monthly = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getMonth, Collectors.averagingDouble(Stock::getVolume)));

			return monthly;

		}

		else {

			System.out.print("Enter correct parameters");
			return null;
		}

	}
////////////////////////                WEEKLY SECTOR         ////////////////////////

	public Map<String, Double> WeeklySector(String sector, String startDate, String endDate, String type)
			throws ParseException {
		List<Company> companies = getBySector(sector);
		List<Stock> stocks = new ArrayList<>();
		for (Company company : companies) {
			stocks.addAll(company.getStocks());
		}
		List<Stock> stocksnew = new ArrayList<>();
		Date sDate = converter.parse(startDate);
		Date eDate = converter.parse(endDate);
		for (Stock stock : stocks) {
			Date nowDate = converter.parse(stock.getDate());
			if ((nowDate.after(sDate) && nowDate.before(eDate)) || nowDate.equals(sDate) || nowDate.equals(eDate)) {
				stocksnew.add(stock);
			}
		}

		if (type.contentEquals("price")) {
			Map<String, Double> value = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getWeek, Collectors.averagingDouble(Stock::getClose)));

			Map<String, Double> weekly = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			
			return weekly;

		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> value = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getWeek, Collectors.averagingDouble(Stock::getVolume)));

			Map<String, Double> weekly = value.entrySet().stream().sorted(comparingByKey())
					.collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
			
			return weekly;
	
		}

		else {
			System.out.print("Enter correct parameters");
			return null;
		}

	}

//////////////////////////////////              MONTHLY SECTOR          ////////////////////////////////////
	public Map<String, Double> MonthlySector(String sector, String startDate, String endDate, String type)
			throws ParseException {
		List<Company> companies = getBySector(sector);
		List<Stock> stocks = new ArrayList<>();
		for (Company company : companies) {
			stocks.addAll(company.getStocks());
		}
		List<Stock> stocksnew = new ArrayList<>();
		Date sDate = converter.parse(startDate);
		Date eDate = converter.parse(endDate);
		for (Stock stock : stocks) {
			Date nowDate = converter.parse(stock.getDate());
			if ((nowDate.after(sDate) && nowDate.before(eDate)) || nowDate.equals(sDate) || nowDate.equals(eDate)) {
				stocksnew.add(stock);
			}
		}

		if (type.contentEquals("price")) {
			Map<String, Double> monthly = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getMonth, Collectors.averagingDouble(Stock::getClose)));

			return monthly;

		}

		else if (type.contentEquals("volume")) {
			Map<String, Double> monthly = stocksnew.stream()
					.collect(Collectors.groupingBy(Stock::getMonth, Collectors.averagingDouble(Stock::getVolume)));

			return monthly;
		}

		else {
			System.out.print("Enter correct parameters");
			return null;
		}

	}

///////////////////////    FUNCTION FOR DAILY WEEKLY MONTHLY FOR A SECTOR               ///////////////////////////
	public Map<String, Double> DataSector(String sector, String startDate, String endDate, String type, String range)
			throws ParseException {
		if (range.contentEquals("daily")) {
			return DailySector(sector, startDate, endDate, type);
		}

		else if (range.contentEquals("weekly")) {
			return WeeklySector(sector, startDate, endDate, type);
		}

		else if (range.contentEquals("monthly")) {
			return MonthlySector(sector, startDate, endDate, type);
		}

		else {

			System.out.print("Enter correct parameters");
			return null;
		}
	}

/////////////////////          FUNCTION FOR DAILY WEEKLY MONTHLY FOR A COMPANY           ////////////////////////////
	public Map<String, Double> DataCompany(String ticker, String startDate, String endDate, String type, String range)
			throws ParseException {
		if (range.contentEquals("daily")) {
			return DailyCompany(ticker, startDate, endDate, type);
		}

		else if (range.contentEquals("weekly")) {
			return WeeklyCompany(ticker, startDate, endDate, type);
		}

		else if (range.contentEquals("monthly")) {
			return MonthlyCompany(ticker, startDate, endDate, type);
		}

		else {

			System.out.print("Enter correct parameters");
			return null;
		}
	}

	// List of objects with daily data for a company
	public List<DailyData> gridCompany(String ticker, String startDate, String endDate) throws ParseException {

		Company company = getByTicker(ticker);
		List<Stock> stocks = company.getStocks();
		List<DailyData> objList = new ArrayList<>();

		Date sDate = converter.parse(startDate);
		Date eDate = converter.parse(endDate);

		for (Stock stock : stocks) {

			Date nowDate = converter.parse(stock.getDate());
			if ((nowDate.after(sDate) && nowDate.before(eDate)) || nowDate.equals(sDate) || nowDate.equals(eDate)) {

				DailyData dailyData = new DailyData();
				dailyData.setDate(stock.getDate());
				dailyData.setPrice(stock.getClose());
				dailyData.setVolume(stock.getVolume());
				dailyData.setCompanyName(company.getName());
				dailyData.setSector(company.getSector());
				dailyData.setTicker(company.getTicker());
				objList.add(dailyData);
			}
		}
		return objList;
	}

	// List of objects with daily data for a sector
	public List<List<DailyData>> gridSector(String sector, String startDate, String endDate) throws ParseException {
		List<Company> companies = getBySector(sector);
		List<List<DailyData>> nestedList = new ArrayList<List<DailyData>>();
		for (Company company : companies) {
			nestedList.add(gridCompany(company.getTicker(), startDate, endDate));
		}
		return nestedList;
	}
	
	
	
	// Companies with sector selected, returns only companies 
	public Map<String,List<Double>> ChartCompanySector(List<String> tickerList, List<String> sectorList, String type) {

		Map<String, List<Double>> Map3 = new HashMap<>();
		for (String ticker : tickerList) {
			Company company = getByTicker(ticker);
			for (String sector : sectorList) {
				if (company.getSector().equalsIgnoreCase(sector)) {
					AverageValues obj = CompanyAverage(ticker, type);
					Map3.put(company.getName(), Arrays.asList(obj.getPreCovidValue(), obj.getPostCovidValue()));
				}
			}
		}

		return Map3;
	}

	
	// Companies with sector selected, returns companies and avg values of sectors
	public Map<String,List<Double>> AvgChartCompanySector(List<String> tickerList, List<String> sectorList, String type) {

		List<String>sectors = new ArrayList<>();

		Map<String, List<Double>> Map3 = new HashMap<>();
		for (String ticker : tickerList) {
			Company company = getByTicker(ticker);
			for (String sector : sectorList) {
				if (company.getSector().equalsIgnoreCase(sector)) {
					sectors.add(sector);
					AverageValues obj = CompanyAverage(ticker, type);
					Map3.put(company.getName(), Arrays.asList(obj.getPreCovidValue(), obj.getPostCovidValue()));
				}
			}
		}
		for(String sector: sectors) {
		AverageValues obj = SectorAverage(sector,type);
		Map3.put(sector,Arrays.asList(obj.getPreCovidValue(), obj.getPostCovidValue()));
		}
		return Map3;
	}
	
	//Companies Selected return only companies
	
	public Map<String,List<Double>> ChartCompany(List<String> tickerList, String type) {
		Map<String, List<Double>> Map3 = new HashMap<>();
		for(String ticker: tickerList) {
			Company company = getByTicker(ticker);
			AverageValues obj = CompanyAverage(ticker, type);
			Map3.put(company.getName(), Arrays.asList(obj.getPreCovidValue(), obj.getPostCovidValue()));		
		}
		return Map3;
	}
	
	// Sector Selected return only Avg values of sectors	
	
	public Map<String,List<Double>> ChartSector(List<String> sectorList, String type) {
		Map<String, List<Double>> Map3 = new HashMap<>();
		for(String sector: sectorList) {
			AverageValues obj = SectorAverage(sector, type);
			Map3.put(sector, Arrays.asList(obj.getPreCovidValue(), obj.getPostCovidValue()));		
		}
		return Map3;
	}
	
	
}
